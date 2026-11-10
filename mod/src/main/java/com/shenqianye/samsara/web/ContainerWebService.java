package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.container.ContainerIndexService;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 容器 Web API：列表/读取/写入/手动扫描。
 *
 * <p>所有涉及世界/方块实体的操作都在服务器线程执行；写入前创建容器审计快照，
 * 写入后调用 {@code setChanged} 并广播方块更新。revision 不匹配返回 409。
 */
public final class ContainerWebService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final long TIMEOUT_SECONDS = 12L;

    private final MinecraftServer server;

    public ContainerWebService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> list(Map<String, String> query) {
        String dimension = query.getOrDefault("dimension", "");
        Integer x = intOrNull(query.get("x"));
        Integer z = intOrNull(query.get("z"));
        int radius = intParam(query, "radius", 64);
        String type = query.getOrDefault("type", "");
        return onServer(() -> ContainerIndexService.getInstance().list(dimension, x, z, radius, type));
    }

    public Map<String, Object> read(Map<String, String> query) {
        String dimension = query.getOrDefault("dimension", "");
        Integer x = intOrNull(query.get("x"));
        Integer y = intOrNull(query.get("y"));
        Integer z = intOrNull(query.get("z"));
        if (dimension.isBlank() || x == null || y == null || z == null) {
            return error(400, "需要 dimension、x、y、z");
        }
        return onServer(() -> ContainerIndexService.getInstance().read(dimension, x, y, z));
    }

    public Map<String, Object> scan(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return error(400, "请求体应为 JSON 对象");
        }
        String dimension = string(request, "dimension");
        Integer x = intOrNull(request.get("x"));
        Integer z = intOrNull(request.get("z"));
        int radius = intOrNull(request.get("radius")) == null ? 64 : intOrNull(request.get("radius"));
        Map<String, Object> result = onServer(() ->
                ContainerIndexService.getInstance().scan(dimension, x, z, radius));
        if (Boolean.FALSE.equals(result.get("ok"))) {
            Object retry = result.get("retryAfterMs");
            if (retry instanceof Number number) {
                result = new LinkedHashMap<>(result);
                result.put("retryAfterMs", number.longValue());
                result.put("status", 429);
            }
        }
        return result;
    }

    public Map<String, Object> write(String body, String actor) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return error(400, "请求体应为 JSON 对象");
        }
        String dimension = string(request, "dimension");
        Integer x = intOrNull(request.get("x"));
        Integer y = intOrNull(request.get("y"));
        Integer z = intOrNull(request.get("z"));
        if (dimension.isBlank() || x == null || y == null || z == null) {
            return error(400, "需要 dimension、x、y、z");
        }
        long revision = longParam(request, "revision", -1L);
        List<String> slots = slots(request.get("slots"));
        if (slots == null) {
            return error(400, "slots 必须是字符串数组");
        }
        return onServer(() -> {
            ContainerIndexService service = ContainerIndexService.getInstance();
            Map<String, Object> before = service.read(dimension, x, y, z);
            String snapshot = GSON.toJson(before);
            WebAuditService.get().captureContainer("/api/containers/write", actor,
                    dimension + " " + x + "," + y + "," + z + " revision=" + revision, snapshot);
            ContainerIndexService.WriteResult result = service.write(dimension, x, y, z, revision, slots);
            Map<String, Object> out = result.toModel();
            out.put("status", result.status());
            if (!result.ok() && result.entry() != null) {
                out.put("currentRevision", result.entry().revision);
            }
            return out;
        });
    }

    /**
     * 容器页辅助：把 item id + count 或已有编码转换成写入所需的 Base64(NBT)。
     * 传入 encoded 时只改数量，保留附魔/名字/耐久等组件。
     */
    public Map<String, Object> item(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return error(400, "请求体应为 JSON 对象");
        }
        String encoded = string(request, "encoded");
        String id = string(request, "id");
        int count = intOrNull(request.get("count")) == null ? 1 : intOrNull(request.get("count"));
        if (count < 1) {
            return error(400, "数量至少为 1");
        }
        return onServer(() -> {
            if (server == null) return error(503, "服务器未启动");
            ItemStack stack;
            if (encoded != null && !encoded.isBlank()) {
                stack = LotteryItemCodec.decode(encoded, server.registryAccess());
                if (stack.isEmpty()) return error(400, "物品编码无效");
                stack.setCount(count);
            } else {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier == null) return error(400, "物品 ID 格式不正确");
                Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
                if (item == null || item == Items.AIR) return error(400, "未知物品：" + id);
                stack = new ItemStack(item, count);
            }
            if (stack.getCount() > stack.getMaxStackSize()) {
                return error(400, "数量超过最大堆叠数 " + stack.getMaxStackSize());
            }
            String result = LotteryItemCodec.encode(stack, server.registryAccess());
            if (result.isBlank()) return error(500, "物品编码失败");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("encoded", result);
            String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            out.put("id", itemId);
            out.put("icon", itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId);
            out.put("name", stack.getHoverName().getString());
            out.put("count", stack.getCount());
            out.put("components", stack.getComponents().toString());
            return out;
        });
    }

    /** 供 P3 容器页展示物品图标/名称：只返回已索引条目的轻量模型。 */
    public Map<String, Object> offline(Map<String, String> query) {
        String uuid = query.getOrDefault("uuid", "");
        if (uuid.isBlank()) {
            return error(400, "需要 uuid");
        }
        try {
            java.util.Set<String> items = com.shenqianye.samsara.container
                    .OfflinePlayerInventoryService.getInstance().itemsOf(java.util.UUID.fromString(uuid));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("uuid", uuid);
            out.put("items", new ArrayList<>(items));
            return out;
        } catch (RuntimeException e) {
            return error(400, "uuid 格式不正确");
        }
    }

    private Map<String, Object> onServer(Supplier<Map<String, Object>> action) {
        if (server == null) {
            return error(503, "服务器未启动");
        }
        try {
            if (server.isSameThread()) {
                return action.get();
            }
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return error(500, "容器操作失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> parse(String body) {
        try {
            Map<String, Object> parsed = GSON.fromJson(body, MAP_TYPE);
            return parsed == null ? null : parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> slots(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(item == null ? "" : String.valueOf(item));
        }
        return out;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int intParam(Map<String, String> query, String key, int fallback) {
        Integer value = intOrNull(query.get(key));
        return value == null ? fallback : value;
    }

    private static long longParam(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", message);
        return out;
    }
}
