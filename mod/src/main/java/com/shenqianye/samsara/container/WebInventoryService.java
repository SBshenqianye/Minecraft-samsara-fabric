package com.shenqianye.samsara.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.item.ItemStackModel;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 幽灵管理员背包：与真实玩家无关的 54 格持久物品仓库。
 *
 * <p>数据落在 {@code world/samsara/web-inventory.json}，所有读写都在服务器线程完成。
 * 用于把容器/玩家背包里的物品暂存到 Web，再由管理员取出或继续编辑。
 */
public final class WebInventoryService {
    public static final int SLOTS = 54;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private final Path file;
    private final Object lock = new Object();

    public WebInventoryService(MinecraftServer server) {
        this.server = server;
        this.file = StoragePaths.ready()
                ? StoragePaths.get().world("web-inventory.json") : null;
    }

    public Map<String, Object> read() {
        synchronized (lock) {
            return model(load());
        }
    }

    public Map<String, Object> write(List<String> incoming) {
        synchronized (lock) {
            if (incoming == null) return error(400, "缺少 slots");
            if (incoming.size() > SLOTS) {
                return error(400, "Web 背包最多 " + SLOTS + " 格");
            }
            List<String> slots = new ArrayList<>(incoming);
            while (slots.size() < SLOTS) slots.add("");
            String invalid = validate(slots);
            if (invalid != null) return error(400, invalid);
            if (!save(slots)) return error(500, "Web 背包保存失败");
            return model(slots);
        }
    }

    public Map<String, Object> deposit(String encoded) {
        synchronized (lock) {
            if (encoded == null || encoded.isBlank()) return error(400, "缺少物品编码");
            ItemStack stack = decode(encoded);
            if (stack.isEmpty()) return error(400, "物品编码无效");
            List<String> slots = load();
            int firstEmpty = -1;
            ItemStack incoming = stack.copy();
            for (int i = 0; i < slots.size(); i++) {
                String value = slots.get(i);
                if (value == null || value.isBlank()) {
                    if (firstEmpty < 0) firstEmpty = i;
                    continue;
                }
                ItemStack existing = decode(value);
                if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
                int max = Math.max(1, existing.getMaxStackSize());
                int space = max - existing.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, incoming.getCount());
                existing.setCount(existing.getCount() + move);
                slots.set(i, LotteryItemCodec.encode(existing, registries()));
                incoming.setCount(incoming.getCount() - move);
                if (incoming.isEmpty()) break;
            }
            if (!incoming.isEmpty() && firstEmpty >= 0) {
                slots.set(firstEmpty, LotteryItemCodec.encode(incoming, registries()));
                incoming.setCount(0);
            }
            if (!incoming.isEmpty()) return error(409, "Web 背包已满，无法存入");
            if (!save(slots)) return error(500, "Web 背包保存失败");
            return model(slots);
        }
    }

    public Map<String, Object> withdraw(int index, int count) {
        synchronized (lock) {
            List<String> slots = load();
            if (index < 0 || index >= slots.size()) return error(400, "槽位越界");
            String value = slots.get(index);
            if (value == null || value.isBlank()) return error(404, "该槽位是空的");
            ItemStack stack = decode(value);
            if (stack.isEmpty()) return error(400, "槽位物品编码无效");
            int take = Math.max(1, Math.min(count <= 0 ? stack.getCount() : count, stack.getCount()));
            ItemStack taken = stack.copyWithCount(take);
            if (take >= stack.getCount()) {
                slots.set(index, "");
            } else {
                stack.setCount(stack.getCount() - take);
                slots.set(index, LotteryItemCodec.encode(stack, registries()));
            }
            if (!save(slots)) return error(500, "Web 背包保存失败");
            Map<String, Object> out = model(slots);
            out.put("item", ItemStackModel.compact(taken,
                    LotteryItemCodec.encode(taken, registries())));
            return out;
        }
    }

    private Map<String, Object> model(List<String> slots) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String encoded = slots.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack = decode(encoded);
            if (stack.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>(
                    ItemStackModel.compact(stack, encoded));
            row.put("slot", i);
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("size", SLOTS);
        out.put("slots", new ArrayList<>(slots));
        out.put("items", items);
        return out;
    }

    private List<String> load() {
        List<String> slots = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            for (int i = 0; i < SLOTS; i++) slots.add("");
            return slots;
        }
        try {
            WebInventoryData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    WebInventoryData.class);
            if (data != null && data.slots != null) slots.addAll(data.slots);
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[web-inventory] 读取失败: {}", e.getMessage());
        }
        while (slots.size() < SLOTS) slots.add("");
        if (slots.size() > SLOTS) slots = new ArrayList<>(slots.subList(0, SLOTS));
        return slots;
    }

    private boolean save(List<String> slots) {
        if (file == null) return false;
        WebInventoryData data = new WebInventoryData();
        data.slots = new ArrayList<>(slots);
        data.updatedAt = System.currentTimeMillis();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[web-inventory] 保存失败: {}", e.getMessage());
            return false;
        }
    }

    private String validate(List<String> slots) {
        for (int i = 0; i < slots.size(); i++) {
            String encoded = slots.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack = decode(encoded);
            if (stack.isEmpty()) return "第 " + i + " 格物品编码无效";
            if (stack.getCount() <= 0 || stack.getCount() > Math.max(1, stack.getMaxStackSize())) {
                return "第 " + i + " 格数量超出上限";
            }
        }
        return null;
    }

    private ItemStack decode(String encoded) {
        RegistryAccess registries = registries();
        return registries == null ? ItemStack.EMPTY
                : LotteryItemCodec.decode(encoded, registries);
    }

    private RegistryAccess registries() {
        return server == null ? null : server.registryAccess();
    }

    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", message);
        return out;
    }

    private static final class WebInventoryData {
        public List<String> slots = new ArrayList<>();
        public long updatedAt;
    }
}
