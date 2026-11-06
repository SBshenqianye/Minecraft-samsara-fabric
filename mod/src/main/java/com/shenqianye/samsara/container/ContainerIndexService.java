package com.shenqianye.samsara.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.lottery.LotteryItemCodec;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 全服容器位置/内容索引。
 *
 * <p>索引只记录方块容器（{@link Container} 方块实体）；已加载区块优先读实时
 * {@link Container}，未加载区块读磁盘缓存。区块加载只入队，由主线程每 tick
 * 限量处理；容器 {@code setItem/setChanged} 只标记单个容器 dirty，不做全服扫描。
 * 磁盘按「维度 + region」分片，避免单个文件过大。
 */
public final class ContainerIndexService {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int CHUNKS_PER_TICK = 2;
    private static final int CONTAINERS_PER_TICK = 24;
    private static final long FLUSH_INTERVAL_MS = 2000L;
    private static final long SCAN_COOLDOWN_MS = 3000L;
    private static final int MAX_LIST = 500;

    private static final ContainerIndexService INSTANCE = new ContainerIndexService();

    private final Object lock = new Object();
    private final Map<ContainerPos, ContainerEntry> entries = new HashMap<>();
    private final Set<ContainerPos> dirtyContainers = new LinkedHashSet<>();
    private final Set<ShardKey> dirtyShards = new HashSet<>();
    private final Deque<ChunkKey> chunkQueue = new ArrayDeque<>();
    private final Set<ChunkKey> chunkQueueSet = new HashSet<>();

    private MinecraftServer server;
    private RegistryAccess registries;
    private Path root;
    private ExecutorService io;
    private volatile boolean ready;
    private boolean flushScheduled;
    private long lastFlushAt;
    private long lastScanAt;
    private long lastScanQueued;

    private ContainerIndexService() {
    }

    public static ContainerIndexService getInstance() {
        return INSTANCE;
    }

    public boolean isReady() {
        return ready;
    }

    /** SERVER_STARTING：读取旧分片；随后区块加载事件会逐步刷新实时内容。 */
    public void init(MinecraftServer server) {
        synchronized (lock) {
            this.server = server;
            this.registries = server.registryAccess();
            this.root = StoragePaths.get().worldDataRoot().resolve("containers");
            this.entries.clear();
            this.dirtyContainers.clear();
            this.dirtyShards.clear();
            this.chunkQueue.clear();
            this.chunkQueueSet.clear();
            this.lastFlushAt = 0L;
            this.lastScanAt = 0L;
            if (io == null || io.isShutdown()) {
                io = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "samsara-container-index");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            long started = System.nanoTime();
            int loaded = loadShards();
            ready = true;
            long ms = (System.nanoTime() - started) / 1_000_000L;
            Samsara.LOGGER.info("[container] 容器索引已加载：{} 个容器，{} 个分片，耗时 {} ms",
                    entries.size(), loaded, ms);
        }
    }

    /** SERVER_STOPPING：把所有缓存分片同步落盘，再关闭异步线程。 */
    public void onServerStopping() {
        synchronized (lock) {
            ready = false;
            flushAllShards();
        }
        ExecutorService current;
        synchronized (lock) {
            current = io;
            io = null;
        }
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Samsara.LOGGER.info("[container] 容器索引已保存并停止");
    }

    /** 区块加载：只入队，真正扫描由 {@link #tick(MinecraftServer)} 限量执行。 */
    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        if (!ready || level == null || chunk == null) {
            return;
        }
        synchronized (lock) {
            ChunkKey key = new ChunkKey(dimension(level), chunk.getPos().x(), chunk.getPos().z());
            if (chunkQueueSet.add(key)) {
                chunkQueue.addLast(key);
            }
        }
    }

    /** 区块卸载：在数据消失前同步复制快照，随后异步序列化。 */
    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (!ready || level == null || chunk == null) {
            return;
        }
        synchronized (lock) {
            ChunkKey key = new ChunkKey(dimension(level), chunk.getPos().x(), chunk.getPos().z());
            chunkQueueSet.remove(key);
            refreshChunkNow(level, chunk, true);
        }
    }

    /** 容器 setItem/setChanged：只标记单个位置，不触发区块扫描。 */
    public void markDirty(BlockEntity entity) {
        if (!ready || entity == null || !(entity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        synchronized (lock) {
            dirtyContainers.add(new ContainerPos(dimension(level), entity.getBlockPos().getX(),
                    entity.getBlockPos().getY(), entity.getBlockPos().getZ()));
        }
    }

    public void tick(MinecraftServer server) {
        if (!ready) {
            return;
        }
        synchronized (lock) {
            if (this.server == null) {
                this.server = server;
            }
            int chunks = 0;
            while (chunks < CHUNKS_PER_TICK && !chunkQueue.isEmpty()) {
                ChunkKey key = chunkQueue.removeFirst();
                chunkQueueSet.remove(key);
                ServerLevel level = level(key.dimension());
                if (level != null) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
                    if (chunk != null) {
                        refreshChunkNow(level, chunk, false);
                    }
                }
                chunks++;
            }
            int containers = 0;
            while (containers < CONTAINERS_PER_TICK && !dirtyContainers.isEmpty()) {
                ContainerPos pos = dirtyContainers.iterator().next();
                dirtyContainers.remove(pos);
                refreshContainerNow(pos);
                containers++;
            }
            long now = System.currentTimeMillis();
            if (!dirtyShards.isEmpty() && now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                lastFlushAt = now;
                scheduleFlush();
            }
        }
    }

    // ---- 查询 ----

    public Map<String, Object> list(String dimension, Integer x, Integer z, int radius, String type) {
        synchronized (lock) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String dimFilter = dimension == null || dimension.isBlank() ? null : dimension;
            String typeFilter = type == null || type.isBlank() ? null : type;
            List<ContainerEntry> sorted = new ArrayList<>(entries.values());
            sorted.sort(Comparator.comparing((ContainerEntry e) -> e.dimension)
                    .thenComparingInt(e -> e.x)
                    .thenComparingInt(e -> e.y)
                    .thenComparingInt(e -> e.z));
            for (ContainerEntry entry : sorted) {
                if (dimFilter != null && !dimFilter.equals(entry.dimension)) continue;
                if (typeFilter != null && !typeFilter.equals(entry.type)
                        && !entry.type.contains(typeFilter)) continue;
                if (x != null && z != null) {
                    int r = Math.max(0, radius);
                    if (Math.abs(entry.x - x) > r || Math.abs(entry.z - z) > r) continue;
                }
                rows.add(entry.toModel(false, sourceOf(entry)));
                if (rows.size() >= MAX_LIST) break;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dimension", dimFilter == null ? "" : dimFilter);
            out.put("radius", Math.max(0, radius));
            out.put("type", typeFilter == null ? "" : typeFilter);
            out.put("total", rows.size());
            out.put("containers", rows);
            out.put("indexSize", entries.size());
            return out;
        }
    }

    public Map<String, Object> read(String dimension, int x, int y, int z) {
        synchronized (lock) {
            ContainerPos key = new ContainerPos(dimension, x, y, z);
            ServerLevel level = level(dimension);
            if (level != null) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk != null) {
                    BlockEntity entity = level.getBlockEntity(new BlockPos(x, y, z));
                    if (entity instanceof Container container) {
                        ContainerEntry fresh = snapshotNow(level, entity.getBlockPos(), entity, container,
                                entries.get(key));
                        entries.put(key, fresh);
                        markShardDirty(fresh);
                        return okRead(fresh, "loaded");
                    }
                }
            }
            ContainerEntry cached = entries.get(key);
            if (cached == null) {
                cached = loadEntryFromShard(key);
                if (cached != null) {
                    entries.put(key, cached);
                }
            }
            if (cached == null) {
                return error("容器不存在或尚未建立索引；可先执行 /api/containers/scan");
            }
            return okRead(cached, "cache");
        }
    }

    /** 保险理赔用：只比对索引中的编码，不加载区块。 */
    public boolean containsEncoded(String encoded, boolean allDimensions, String currentDimension) {
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        synchronized (lock) {
            for (ContainerEntry entry : entries.values()) {
                if (!allDimensions && currentDimension != null
                        && !currentDimension.equals(entry.dimension)) {
                    continue;
                }
                if (entry.matches(encoded)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int indexSize() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** 单测入口：直接注入内存条目，不触碰服务器/磁盘。 */
    void putForTest(ContainerEntry entry) {
        synchronized (lock) {
            entries.put(new ContainerPos(entry.dimension, entry.x, entry.y, entry.z), entry);
        }
    }

    void clearForTest() {
        synchronized (lock) {
            entries.clear();
        }
    }

    // ---- 写入（P2 使用；P1 先提供服务器线程执行入口） ----

    public WriteResult write(String dimension, int x, int y, int z, long expectedRevision,
                             List<String> encodedSlots) {
        synchronized (lock) {
            ServerLevel level = level(dimension);
            if (level == null) {
                return WriteResult.error(404, "维度不存在：" + dimension);
            }
            BlockPos pos = new BlockPos(x, y, z);
            LevelChunk chunk;
            try {
                // 只加载目标区块；不做批量区块加载。
                chunk = level.getChunk(x >> 4, z >> 4);
            } catch (RuntimeException e) {
                return WriteResult.error(500, "目标区块加载失败：" + e.getMessage());
            }
            if (chunk == null) {
                return WriteResult.error(500, "目标区块不可用");
            }
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) {
                return WriteResult.error(400, "该坐标没有可编辑的容器");
            }
            ContainerPos key = new ContainerPos(dimension, x, y, z);
            ContainerEntry old = entries.get(key);
            if (old == null) {
                old = snapshotNow(level, pos, entity, container, null);
                entries.put(key, old);
                markShardDirty(old);
            }
            if (expectedRevision >= 0 && expectedRevision != old.revision) {
                return WriteResult.conflict(old, "容器已被其他操作修改（revision " + old.revision + "）");
            }
            if (encodedSlots == null) {
                return WriteResult.error(400, "缺少 slots");
            }
            int size = container.getContainerSize();
            if (encodedSlots.size() != size) {
                return WriteResult.error(400,
                        "槽位数不匹配：容器有 " + size + " 格，请求提供 " + encodedSlots.size() + " 格");
            }
            List<ItemStack> stacks = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String encoded = encodedSlots.get(i);
                if (encoded == null || encoded.isBlank()) {
                    stacks.add(ItemStack.EMPTY);
                    continue;
                }
                ItemStack stack = LotteryItemCodec.decode(encoded, registries);
                if (stack.isEmpty()) {
                    return WriteResult.error(400, "第 " + i + " 格物品编码无效");
                }
                if (stack.getCount() <= 0 || stack.getCount() > Math.max(1, stack.getMaxStackSize())) {
                    return WriteResult.error(400, "第 " + i + " 格数量超出上限");
                }
                stacks.add(stack);
            }
            for (int i = 0; i < size; i++) {
                container.setItem(i, stacks.get(i));
            }
            container.setChanged();
            level.blockEntityChanged(pos);
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            ContainerEntry fresh = snapshotNow(level, pos, entity, container, old);
            entries.put(key, fresh);
            markShardDirty(fresh);
            return WriteResult.ok(fresh);
        }
    }

    public Map<String, Object> scan(String dimension, Integer x, Integer z, int radius) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now - lastScanAt < SCAN_COOLDOWN_MS) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", "扫描过于频繁，请稍后再试");
                out.put("retryAfterMs", SCAN_COOLDOWN_MS - (now - lastScanAt));
                return out;
            }
            lastScanAt = now;
            List<ServerLevel> levels = new ArrayList<>();
            if (dimension != null && !dimension.isBlank()) {
                ServerLevel level = level(dimension);
                if (level == null) {
                    return Map.of("ok", false, "error", "维度不存在：" + dimension);
                }
                levels.add(level);
            } else if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    levels.add(level);
                }
            }
            int queuedChunks = 0;
            int queuedContainers = 0;
            for (ServerLevel level : levels) {
                String dim = dimension(level);
                if (x != null && z != null) {
                    int r = Math.max(0, radius);
                    int minChunkX = (x - r) >> 4;
                    int maxChunkX = (x + r) >> 4;
                    int minChunkZ = (z - r) >> 4;
                    int maxChunkZ = (z + r) >> 4;
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                            if (chunk == null) continue;
                            refreshChunkNow(level, chunk, false);
                            queuedChunks++;
                            queuedContainers += chunk.getBlockEntities().size();
                        }
                    }
                } else {
                    // 没有坐标时只刷新索引中已知且已加载的区块，避免强制加载大量区块。
                    Set<ChunkKey> chunks = new HashSet<>();
                    for (ContainerEntry entry : entries.values()) {
                        if (!dim.equals(entry.dimension)) continue;
                        ChunkKey key = new ChunkKey(dim, entry.x >> 4, entry.z >> 4);
                        if (chunks.add(key)) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
                            if (chunk != null) {
                                refreshChunkNow(level, chunk, false);
                                queuedChunks++;
                                queuedContainers += chunk.getBlockEntities().size();
                            }
                        }
                    }
                }
            }
            lastScanQueued = queuedChunks;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("queuedChunks", queuedChunks);
            out.put("queuedContainers", queuedContainers);
            out.put("indexSize", entries.size());
            out.put("dimension", dimension == null ? "" : dimension);
            out.put("cooldownMs", SCAN_COOLDOWN_MS);
            return out;
        }
    }

    // ---- 内部：刷新与持久化 ----

    private void refreshChunkNow(ServerLevel level, LevelChunk chunk, boolean snapshot) {
        String dim = dimension(level);
        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            if (!(e.getValue() instanceof Container container)) continue;
            BlockPos pos = e.getKey();
            ContainerPos key = new ContainerPos(dim, pos.getX(), pos.getY(), pos.getZ());
            if (snapshot) {
                ContainerEntry entry = snapshotNow(level, pos, e.getValue(), container, entries.get(key));
                entries.put(key, entry);
                markShardDirty(entry);
            } else {
                dirtyContainers.add(key);
            }
        }
    }

    private void refreshContainerNow(ContainerPos pos) {
        ServerLevel level = level(pos.dimension());
        if (level == null) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x() >> 4, pos.z() >> 4);
        if (chunk == null) return;
        BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
        BlockEntity entity = level.getBlockEntity(blockPos);
        if (!(entity instanceof Container container)) {
            ContainerEntry removed = entries.remove(pos);
            if (removed != null) {
                markShardDirty(removed);
            }
            return;
        }
        ContainerEntry entry = snapshotNow(level, blockPos, entity, container, entries.get(pos));
        entries.put(pos, entry);
        markShardDirty(entry);
    }

    private ContainerEntry snapshotNow(ServerLevel level, BlockPos pos, BlockEntity entity,
                                       Container container, ContainerEntry old) {
        ContainerEntry entry = old == null ? new ContainerEntry() : old.copy();
        entry.dimension = dimension(level);
        entry.x = pos.getX();
        entry.y = pos.getY();
        entry.z = pos.getZ();
        entry.type = entity == null ? "minecraft:container"
                : String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
        entry.size = container.getContainerSize();
        List<String> slots = new ArrayList<>(entry.size);
        int nonEmpty = 0;
        for (int i = 0; i < entry.size; i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty()) {
                slots.add("");
                continue;
            }
            String encoded = LotteryItemCodec.encode(stack, registries);
            if (encoded.isBlank()) {
                slots.add("");
                continue;
            }
            slots.add(encoded);
            nonEmpty++;
        }
        entry.nonEmpty = nonEmpty;
        if (old == null) {
            entry.revision = 1L;
            entry.updatedAt = System.currentTimeMillis();
        } else if (!old.sameContent(entry.type, slots)) {
            entry.revision = old.revision + 1L;
            entry.updatedAt = System.currentTimeMillis();
        } else {
            entry.revision = old.revision;
            entry.updatedAt = old.updatedAt;
        }
        entry.slots = slots;
        return entry;
    }

    private void markShardDirty(ContainerEntry entry) {
        dirtyShards.add(new ShardKey(entry.dimension, entry.x >> 9, entry.z >> 9));
    }

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        ExecutorService executor = io;
        if (executor == null || executor.isShutdown()) {
            flushScheduled = false;
            return;
        }
        executor.execute(() -> {
            try {
                flushDirtyShards();
            } catch (Throwable t) {
                Samsara.LOGGER.warn("[container] 容器索引异步写入失败: {}", t.getMessage());
            } finally {
                synchronized (lock) {
                    flushScheduled = false;
                }
            }
        });
    }

    private void flushDirtyShards() {
        Map<ShardKey, List<ContainerEntry>> copies = new HashMap<>();
        synchronized (lock) {
            for (ShardKey key : dirtyShards) {
                copies.put(key, entriesForShard(key));
            }
            dirtyShards.clear();
        }
        for (Map.Entry<ShardKey, List<ContainerEntry>> e : copies.entrySet()) {
            writeShard(e.getKey(), e.getValue());
        }
    }

    private void flushAllShards() {
        Map<ShardKey, List<ContainerEntry>> copies = new HashMap<>();
        Set<ShardKey> shards = new HashSet<>();
        for (ContainerEntry entry : entries.values()) {
            shards.add(new ShardKey(entry.dimension, entry.x >> 9, entry.z >> 9));
        }
        shards.addAll(dirtyShards);
        for (ShardKey key : shards) {
            copies.put(key, entriesForShard(key));
        }
        dirtyShards.clear();
        for (Map.Entry<ShardKey, List<ContainerEntry>> e : copies.entrySet()) {
            writeShard(e.getKey(), e.getValue());
        }
    }

    private List<ContainerEntry> entriesForShard(ShardKey key) {
        List<ContainerEntry> out = new ArrayList<>();
        for (ContainerEntry entry : entries.values()) {
            if (!entry.dimension.equals(key.dimension())) continue;
            if ((entry.x >> 9) != key.regionX() || (entry.z >> 9) != key.regionZ()) continue;
            // 只持久化非空容器；空容器留在内存中，随区块加载恢复。
            if (entry.empty()) continue;
            out.add(entry.copy());
        }
        return out;
    }

    private void writeShard(ShardKey key, List<ContainerEntry> list) {
        if (root == null) return;
        Path file = shardPath(key);
        try {
            Files.createDirectories(file.getParent());
            ContainerShard shard = new ContainerShard();
            shard.dimension = key.dimension();
            shard.regionX = key.regionX();
            shard.regionZ = key.regionZ();
            shard.entries = list;
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(shard), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[container] 写入分片 {} 失败: {}", file, e.getMessage());
        }
    }

    private int loadShards() {
        if (root == null || !Files.isDirectory(root)) return 0;
        int count = 0;
        try (var stream = Files.walk(root)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))::iterator) {
                try {
                    ContainerShard shard = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                            ContainerShard.class);
                    if (shard == null || shard.entries == null) continue;
                    for (ContainerEntry entry : shard.entries) {
                        if (entry == null || entry.dimension == null || entry.dimension.isBlank()) continue;
                        if (entry.slots == null) entry.slots = new ArrayList<>();
                        entries.put(new ContainerPos(entry.dimension, entry.x, entry.y, entry.z), entry);
                    }
                    count++;
                } catch (RuntimeException | IOException e) {
                    Samsara.LOGGER.warn("[container] 跳过损坏的分片 {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[container] 扫描容器索引目录失败: {}", e.getMessage());
        }
        return count;
    }

    private ContainerEntry loadEntryFromShard(ContainerPos key) {
        Path file = shardPath(new ShardKey(key.dimension(), key.x() >> 9, key.z() >> 9));
        if (!Files.isRegularFile(file)) return null;
        try {
            ContainerShard shard = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    ContainerShard.class);
            if (shard == null || shard.entries == null) return null;
            for (ContainerEntry entry : shard.entries) {
                if (entry != null && key.dimension().equals(entry.dimension)
                        && entry.x == key.x() && entry.y == key.y() && entry.z == key.z()) {
                    if (entry.slots == null) entry.slots = new ArrayList<>();
                    return entry;
                }
            }
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[container] 读取分片 {} 失败: {}", file, e.getMessage());
        }
        return null;
    }

    private Path shardPath(ShardKey key) {
        String folder = key.dimension().replaceAll("[^A-Za-z0-9_.-]", "_");
        return root.resolve(folder).resolve("r." + key.regionX() + "." + key.regionZ() + ".json");
    }

    private String sourceOf(ContainerEntry entry) {
        ServerLevel level = level(entry.dimension);
        if (level != null) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(entry.x >> 4, entry.z >> 4);
            if (chunk != null && level.getBlockEntity(new BlockPos(entry.x, entry.y, entry.z)) instanceof Container) {
                return "loaded";
            }
        }
        return "cache";
    }

    private Map<String, Object> okRead(ContainerEntry entry, String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("container", entry.toModel(true, source));
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private ServerLevel level(String dimension) {
        if (server == null || dimension == null || dimension.isBlank()) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public record ContainerPos(String dimension, int x, int y, int z) {
    }

    private record ChunkKey(String dimension, int chunkX, int chunkZ) {
    }

    private record ShardKey(String dimension, int regionX, int regionZ) {
    }

    public record WriteResult(boolean ok, int status, String error, ContainerEntry entry) {
        static WriteResult ok(ContainerEntry entry) {
            return new WriteResult(true, 200, "", entry);
        }

        static WriteResult conflict(ContainerEntry entry, String error) {
            return new WriteResult(false, 409, error, entry);
        }

        static WriteResult error(int status, String error) {
            return new WriteResult(false, status, error, null);
        }

        public Map<String, Object> toModel() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", ok);
            if (!ok) out.put("error", error);
            if (entry != null) {
                out.put("container", entry.toModel(true, "loaded"));
            }
            return out;
        }
    }
}
