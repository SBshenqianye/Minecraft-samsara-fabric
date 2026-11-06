package com.shenqianye.samsara.container;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 离线玩家背包/末影箱只读索引。
 *
 * <p>只读 {@code world/playerdata/<uuid>.dat}（实际目录由
 * {@link MinecraftServer#getWorldPath(LevelResource)} 给出），按 mtime + size 缓存
 * 每个文件的物品编码集合；玩家登录/退出时失效，保存后由 mtime 自动失效。
 * 绝不写回玩家文件。
 */
public final class OfflinePlayerInventoryService {
    private static final OfflinePlayerInventoryService INSTANCE = new OfflinePlayerInventoryService();

    private final Map<UUID, CacheEntry> cache = new HashMap<>();
    private Path directory;
    private RegistryAccess registries;
    private long lastScanAt;

    private OfflinePlayerInventoryService() {
    }

    public static OfflinePlayerInventoryService getInstance() {
        return INSTANCE;
    }

    public synchronized void init(MinecraftServer server) {
        cache.clear();
        lastScanAt = 0L;
        if (server == null) {
            directory = null;
            registries = null;
            return;
        }
        registries = server.registryAccess();
        try {
            directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        } catch (RuntimeException e) {
            directory = null;
            Samsara.LOGGER.warn("[container] 无法解析离线玩家数据目录: {}", e.getMessage());
        }
    }

    public synchronized void onServerStopping() {
        cache.clear();
        directory = null;
        registries = null;
    }

    public synchronized void invalidate(UUID uuid) {
        if (uuid != null) {
            cache.remove(uuid);
        }
    }

    public synchronized void invalidateAll() {
        cache.clear();
    }

    /**
     * 理赔前查询：扫描所有离线玩家 .dat，命中返回 true。
     * 只解析 mtime/size 变化的文件；未变化的文件直接用缓存集合。
     */
    public synchronized boolean containsEncoded(String encoded) {
        if (encoded == null || encoded.isBlank() || directory == null || registries == null) {
            return false;
        }
        if (!Files.isDirectory(directory)) {
            return false;
        }
        lastScanAt = System.currentTimeMillis();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path file : (Iterable<Path>) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))::iterator) {
                UUID uuid = parseUuid(file);
                if (uuid == null) continue;
                CacheEntry entry = read(file, uuid);
                if (entry != null && entry.items.contains(encoded)) {
                    return true;
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[container] 扫描离线玩家数据失败: {}", e.getMessage());
        }
        return false;
    }

    /** 只读快照：返回指定离线玩家背包/末影箱的物品编码；不存在返回空集合。 */
    public synchronized Set<String> itemsOf(UUID uuid) {
        if (uuid == null || directory == null || registries == null) {
            return Set.of();
        }
        Path file = directory.resolve(uuid + ".dat");
        if (!Files.isRegularFile(file)) {
            return Set.of();
        }
        CacheEntry entry = read(file, uuid);
        return entry == null ? Set.of() : Set.copyOf(entry.items);
    }

    public synchronized int cachedPlayers() {
        return cache.size();
    }

    public synchronized long lastScanAt() {
        return lastScanAt;
    }

    private CacheEntry read(Path file, UUID uuid) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            CacheEntry cached = cache.get(uuid);
            if (cached != null && cached.mtime == mtime && cached.size == size) {
                return cached;
            }
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Set<String> items = new HashSet<>();
            items.addAll(parseItems(tag.getListOrEmpty("Inventory"), registries));
            items.addAll(parseItems(tag.getListOrEmpty("EnderItems"), registries));
            CacheEntry entry = new CacheEntry(mtime, size, items);
            cache.put(uuid, entry);
            return entry;
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.debug("[container] 读取离线玩家 {} 失败: {}", uuid, e.getMessage());
            return null;
        }
    }

    /** 纯解析入口，便于单测：Inventory/EnderItems 的 CompoundTag 列表 → 物品编码集合。 */
    static Set<String> parseItems(ListTag list, RegistryAccess registries) {
        Set<String> out = new HashSet<>();
        if (list == null || list.isEmpty() || registries == null) {
            return out;
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ItemStack stack = ItemStack.CODEC.parse(ops, entry).result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) continue;
            String encoded = LotteryItemCodec.encode(stack, registries);
            if (!encoded.isBlank()) {
                out.add(encoded);
            }
        }
        return out;
    }

    private static UUID parseUuid(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) return null;
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record CacheEntry(long mtime, long size, Set<String> items) {
    }
}
