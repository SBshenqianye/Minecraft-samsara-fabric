package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.container.ContainerIndexService;
import com.shenqianye.samsara.container.ContainerViews;
import com.shenqianye.samsara.item.ItemStackModel;
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
 * 响应统一为 {@link ContainerViews} 的 record。
 */
public final class ContainerWebService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final long TIMEOUT_SECONDS = 12L;

    private final MinecraftServer server;

    public ContainerWebService(MinecraftServer server) {
        this.server = server;
    }

    public ContainerViews.Outcome list(Map<String, String> query) {
        String dimension = query.getOrDefault("dimension", "");
        Integer x = intOrNull(query.get("x"));
        Integer z = intOrNull(query.get("z"));
        int radius = intParam(query, "radius", 64);
        String type = query.getOrDefault("type", "");
        String q = query.getOrDefault("q", "");
        return onServer(() -> ContainerIndexService.getInstance()
                .list(dimension, x, z, radius, type, q));
    }

    public ContainerViews.Outcome read(Map<String, String> query) {
        String dimension = query.getOrDefault("dimension", "");
        Integer x = intOrNull(query.get("x"));
        Integer y = intOrNull(query.get("y"));
        Integer z = intOrNull(query.get("z"));
        if (dimension.isBlank() || x == null || y == null || z == null) {
            return new ContainerViews.StatusError(false, 400, "需要 dimension、x、y、z");
        }
        return onServer(() -> ContainerIndexService.getInstance().read(dimension, x, y, z));
    }

    public ContainerViews.Outcome scan(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return new ContainerViews.StatusError(false, 400, "请求体应为 JSON 对象");
        }
        String dimension = string(request, "dimension");
        Integer x = intOrNull(request.get("x"));
        Integer z = intOrNull(request.get("z"));
        int radius = intOrNull(request.get("radius")) == null ? 64 : intOrNull(request.get("radius"));
        return onServer(() -> ContainerIndexService.getInstance().scan(dimension, x, z, radius));
    }

    public ContainerViews.Outcome write(String body, String actor) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return new ContainerViews.StatusError(false, 400, "请求体应为 JSON 对象");
        }
        String dimension = string(request, "dimension");
        Integer x = intOrNull(request.get("x"));
        Integer y = intOrNull(request.get("y"));
        Integer z = intOrNull(request.get("z"));
        if (dimension.isBlank() || x == null || y == null || z == null) {
            return new ContainerViews.StatusError(false, 400, "需要 dimension、x、y、z");
        }
        long revision = longParam(request, "revision", -1L);
        List<String> slots = slots(request.get("slots"));
        if (slots == null) {
            return new ContainerViews.StatusError(false, 400, "slots 必须是字符串数组");
        }
        return onServer(() -> {
            ContainerIndexService service = ContainerIndexService.getInstance();
            String snapshot = GSON.toJson(service.read(dimension, x, y, z));
            WebAuditService.get().captureContainer("/api/containers/write", actor,
                    dimension + " " + x + "," + y + "," + z + " revision=" + revision, snapshot);
            ContainerIndexService.WriteResult result = service.write(dimension, x, y, z, revision, slots);
            return result.toModel();
        });
    }

    /**
     * 容器页辅助：把 item id + count 或已有编码转换成写入所需的 Base64(NBT)。
     * 传入 encoded 时只改数量，保留附魔/名字/耐久等组件。
     * <p>
     * 响应里是共享物品模型（{@code ItemStackModel.describe}），仍为 Map。
     */
    public ContainerViews.Outcome item(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) {
            return statusError(400, "请求体应为 JSON 对象");
        }
        String encoded = string(request, "encoded");
        String id = string(request, "id");
        int count = intOrNull(request.get("count")) == null ? 1 : intOrNull(request.get("count"));
        boolean unlimited = Boolean.TRUE.equals(request.get("unlimited"));
        if (count < 1) {
            return statusError(400, "数量至少为 1");
        }
        return onServer(() -> {
            if (server == null) return statusError(503, "服务器未启动");
            ItemStack stack;
            if (encoded != null && !encoded.isBlank()) {
                stack = LotteryItemCodec.decode(encoded, server.registryAccess());
                if (stack.isEmpty()) return statusError(400, "物品编码无效");
                stack.setCount(count);
            } else {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier == null) return statusError(400, "物品 ID 格式不正确");
                Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
                if (item == null || item == Items.AIR) return statusError(400, "未知物品：" + id);
                stack = new ItemStack(item, count);
            }
            if (!unlimited && stack.getCount() > stack.getMaxStackSize()) {
                return statusError(400, "数量超过最大堆叠数 " + stack.getMaxStackSize());
            }
            String result = LotteryItemCodec.encode(stack, server.registryAccess());
            if (result.isBlank()) return statusError(500, "物品编码失败");
            return new com.shenqianye.samsara.container.ContainerViews.ItemResponse(true,
                    ItemStackModel.describe(stack, server.registryAccess(), result));
        });
    }

    /** 供 P3 容器页展示物品图标/名称：只返回已索引条目的轻量模型。 */
    public ContainerViews.Outcome offline(Map<String, String> query) {
        String uuid = query.getOrDefault("uuid", "");
        if (uuid.isBlank()) {
            return new ContainerViews.StatusError(false, 400, "需要 uuid");
        }
        try {
            java.util.Set<String> items = com.shenqianye.samsara.container
                    .OfflinePlayerInventoryService.getInstance().itemsOf(java.util.UUID.fromString(uuid));
            return new ContainerViews.Offline(true, uuid, new ArrayList<>(items));
        } catch (RuntimeException e) {
            return new ContainerViews.StatusError(false, 400, "uuid 格式不正确");
        }
    }

    private ContainerViews.Outcome onServer(Supplier<? extends ContainerViews.Outcome> action) {
        if (server == null) {
            return new ContainerViews.StatusError(false, 503, "服务器未启动");
        }
        try {
            if (server.isSameThread()) {
                return action.get();
            }
            CompletableFuture<ContainerViews.Outcome> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ContainerViews.StatusError(false, 500, "容器操作失败：" + e.getMessage());
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

    private static ContainerViews.StatusError statusError(int status, String message) {
        return new ContainerViews.StatusError(false, status, message);
    }
}
