package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.item.ItemStackModel;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 物品结构化编辑 API：describe / build / schema。 */
public final class ItemWebService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final long TIMEOUT_SECONDS = 12L;

    private final MinecraftServer server;

    public ItemWebService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> schema() {
        return onServer(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            RegistryAccess registries = registries();
            if (registries == null) return error(503, "服务器未启动");
            out.put("ok", true);
            out.put("schema", ItemStackModel.schema(registries));
            return out;
        });
    }

    /** 把 encoded 或 id+count 解析成结构化模型。 */
    public Map<String, Object> describe(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return onServer(() -> {
            RegistryAccess registries = registries();
            if (registries == null) return error(503, "服务器未启动");
            ItemStack stack = baseStack(request, registries);
            if (stack == null) return error(400, "无效的物品");
            if (stack.isEmpty()) return error(400, "物品编码为空");
            String encoded = LotteryItemCodec.encode(stack, registries);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("item", ItemStackModel.describe(stack, registries, encoded));
            return out;
        });
    }

    /** 应用结构化字段，返回新的 encoded 与模型。 */
    public Map<String, Object> build(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return onServer(() -> {
            RegistryAccess registries = registries();
            if (registries == null) return error(503, "服务器未启动");
            ItemStack stack = baseStack(request, registries);
            if (stack == null) return error(400, "无效的物品");
            if (stack.isEmpty()) {
                ItemStack created = stackFromId(request, registries);
                if (created == null) return error(400, "无效的物品 ID");
                stack = created;
            }
            int count = intOr(request.get("count"), stack.getCount());
            if (count < 1) count = 1;
            boolean unlimited = Boolean.TRUE.equals(request.get("unlimited"));
            stack.setCount(unlimited ? count : Math.min(count, Math.max(1, stack.getMaxStackSize())));
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = request.get("patch") instanceof Map<?, ?>
                    ? (Map<String, Object>) request.get("patch") : request;
            List<String> errors = ItemStackModel.apply(stack, registries, patch);
            String encoded = LotteryItemCodec.encode(stack, registries);
            if (encoded.isBlank()) return error(500, "物品编码失败");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("errors", errors);
            out.put("item", ItemStackModel.describe(stack, registries, encoded));
            return out;
        });
    }

    /** 直接解析完整物品 SNBT，供“原始 NBT”编辑。 */
    public Map<String, Object> raw(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return onServer(() -> {
            RegistryAccess registries = registries();
            if (registries == null) return error(503, "服务器未启动");
            ItemStack stack = ItemStackModel.parseSnbt(str(request.get("snbt")), registries);
            if (stack.isEmpty()) return error(400, "SNBT 无法解析为物品");
            int count = intOr(request.get("count"), stack.getCount());
            if (count < 1) count = 1;
            boolean unlimited = Boolean.TRUE.equals(request.get("unlimited"));
            stack.setCount(unlimited ? count : Math.min(count, Math.max(1, stack.getMaxStackSize())));
            String encoded = LotteryItemCodec.encode(stack, registries);
            if (encoded.isBlank()) return error(500, "物品编码失败");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("item", ItemStackModel.describe(stack, registries, encoded));
            return out;
        });
    }

    /** 3×3 合成：按原版 RecipeManager 匹配，返回结果与一次合成后的剩余物品。 */
    public Map<String, Object> craft(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return onServer(() -> {
            RegistryAccess registries = registries();
            if (registries == null) return error(503, "服务器未就绪");
            List<ItemStack> grid = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) grid.add(ItemStack.EMPTY);
            Object raw = request.get("slots");
            if (raw instanceof List<?> list) {
                for (int i = 0; i < Math.min(9, list.size()); i++) {
                    Object value = list.get(i);
                    if (value == null) continue;
                    ItemStack stack = LotteryItemCodec.decode(String.valueOf(value), registries);
                    if (!stack.isEmpty()) grid.set(i, stack);
                }
            }
            CraftingInput input = CraftingInput.of(3, 3, grid);
            var holder = server.getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, input, server.overworld());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            if (holder.isEmpty()) {
                out.put("result", null);
                out.put("remaining", List.of());
                out.put("recipe", "");
                return out;
            }
            RecipeHolder<CraftingRecipe> recipe = holder.get();
            ItemStack result = recipe.value().assemble(input);
            if (result == null || result.isEmpty()) {
                out.put("result", null);
                out.put("remaining", List.of());
                out.put("recipe", recipe.id().identifier().toString());
                return out;
            }
            String encoded = LotteryItemCodec.encode(result, registries);
            out.put("result", ItemStackModel.compact(result, encoded));
            List<ItemStack> remainders = recipe.value().getRemainingItems(input);
            List<Map<String, Object>> remaining = new java.util.ArrayList<>(
                    java.util.Collections.nCopies(9, null));
            int remainderIndex = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = null;
                if (remainders.size() == 9) {
                    stack = remainders.get(i);
                } else if (!grid.get(i).isEmpty() && remainderIndex < remainders.size()) {
                    stack = remainders.get(remainderIndex++);
                }
                if (stack != null && !stack.isEmpty()) {
                    remaining.set(i, ItemStackModel.compact(stack,
                            LotteryItemCodec.encode(stack, registries)));
                }
            }
            out.put("remaining", remaining);
            out.put("recipe", recipe.id().identifier().toString());
            return out;
        });
    }

    private ItemStack baseStack(Map<String, Object> request, RegistryAccess registries) {
        String encoded = str(request.get("encoded"));
        if (!encoded.isBlank()) {
            return LotteryItemCodec.decode(encoded, registries);
        }
        return stackFromId(request, registries);
    }

    private ItemStack stackFromId(Map<String, Object> request, RegistryAccess registries) {
        String id = str(request.get("id"));
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
        if (item == null || item == Items.AIR) return null;
        return new ItemStack(item, Math.max(1, intOr(request.get("count"), 1)));
    }

    private RegistryAccess registries() {
        return server == null ? null : server.registryAccess();
    }

    private Map<String, Object> onServer(Supplier<Map<String, Object>> action) {
        if (server == null) return error(503, "服务器未启动");
        try {
            if (server.isSameThread()) return action.get();
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
            return error(500, "物品操作失败：" + e.getMessage());
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

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
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
