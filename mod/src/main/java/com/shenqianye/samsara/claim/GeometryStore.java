package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 几何图形存储（设计文档 §6.4 GeometryStore）——按维度存储。
 * <p>
 * 将已注册区块按连通性分组为"几何图形"（geometry group），每组是一个连通区域。
 * 每个维度（overworld/nether/end）拥有独立的图形组集合。
 * 数据持久化至 {@code config/samsara/geometry.json}。
 * <p>
 * 核心流程：
 *   添加旗帜 → 注册区块 → 检查邻居 → 合并到已有图形 / 创建新图形 → 更新索引
 *   拆除旗帜 → 移除区块 → 检查连通性 → 拆分图形 / 删除空图形 → 更新索引
 * <p>
 * 设计原则：写入时慢慢来（合并/拆分），读取时快（白名单 = 所有图形的区块并集）。
 */
public class GeometryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static GeometryStore instance;
    private final Path file;

    /** 每个维度的独立数据 */
    private final Map<String, DimGeoData> dimensions = new LinkedHashMap<>();

    private GeometryStore() {
        Path dir = com.shenqianye.samsara.storage.StoragePaths.get().worldDataRoot()
                .resolve("claims");
        try { Files.createDirectories(dir); } catch (IOException e) { throw new RuntimeException(e); }
        file = dir.resolve("geometry.json");
        load();
    }

    public static synchronized GeometryStore getInstance() {
        if (instance == null) instance = new GeometryStore();
        return instance;
    }

    // ---- 维度数据内部类 ----

    static class DimGeoData {
        /** 图形组 ID → 组内区块集合 */
        final Map<String, Set<ChunkCoord>> groups = new LinkedHashMap<>();
        /** 区块 → 所属图形组 ID（索引，加速查找） */
        final Map<ChunkCoord, String> chunkIndex = new ConcurrentHashMap<>();
        /** 下一个可用组 ID */
        int nextGroupId = 1;
    }

    // ---- 公共 API ----

    /** 获取指定维度的所有受保护区块（白名单） */
    public Set<ChunkCoord> getWhitelist(ResourceKey<Level> dim) {
        DimGeoData data = getOrCreateDim(dim);
        return new HashSet<>(data.chunkIndex.keySet());
    }

    /** 判断指定维度的区块是否受保护 */
    public boolean isProtected(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        DimGeoData data = getOrCreateDim(dim);
        return data.chunkIndex.containsKey(new ChunkCoord(chunkX, chunkZ));
    }

    /**
     * 添加一个区块到几何图形（放置旗帜时调用）。
     * 检查4邻居，合并到已有图形或创建新图形。
     */
    public synchronized void addChunk(ResourceKey<Level> dim, ChunkCoord chunk) {
        DimGeoData data = getOrCreateDim(dim);
        if (data.chunkIndex.containsKey(chunk)) return; // 已存在

        // 查找4邻居所属的图形组
        Set<String> neighborGroups = new LinkedHashSet<>();
        for (ChunkCoord nb : neighbors(chunk)) {
            String gid = data.chunkIndex.get(nb);
            if (gid != null) neighborGroups.add(gid);
        }

        if (neighborGroups.isEmpty()) {
            // 无邻居 → 创建新图形
            String gid = "g" + data.nextGroupId++;
            Set<ChunkCoord> group = new LinkedHashSet<>();
            group.add(chunk);
            data.groups.put(gid, group);
            data.chunkIndex.put(chunk, gid);
        } else if (neighborGroups.size() == 1) {
            // 1个邻居 → 加入该图形
            String gid = neighborGroups.iterator().next();
            data.groups.get(gid).add(chunk);
            data.chunkIndex.put(chunk, gid);
        } else {
            // 多个邻居 → 合并所有图形为一个
            mergeGroups(data, neighborGroups, chunk);
        }
    }

    /**
     * 从几何图形移除一个区块（拆除旗帜时调用）。
     * 移除后检查连通性，必要时拆分图形。
     */
    public synchronized void removeChunk(ResourceKey<Level> dim, ChunkCoord chunk) {
        DimGeoData data = getOrCreateDim(dim);
        String gid = data.chunkIndex.remove(chunk);
        if (gid == null) return;

        Set<ChunkCoord> group = data.groups.get(gid);
        if (group == null) return;
        group.remove(chunk);

        if (group.isEmpty()) {
            data.groups.remove(gid);
            return;
        }

        // 检查连通性：从组内任意区块 BFS，看是否能访问所有区块
        if (isConnected(group)) return;

        // 不连通 → 拆分
        splitGroup(data, gid, group);
    }

    /** 获取指定维度的所有图形组（只读视图，供调试/显示） */
    public Map<String, Set<ChunkCoord>> getGroups(ResourceKey<Level> dim) {
        DimGeoData data = getOrCreateDim(dim);
        return Collections.unmodifiableMap(data.groups);
    }

    /** 清空指定维度的图形 */
    public synchronized void clear(ResourceKey<Level> dim) {
        String key = dim.toString().toString();
        dimensions.remove(key);
    }

    /** 清空所有维度的图形 */
    public synchronized void clearAll() {
        dimensions.clear();
    }

    // ---- 内部逻辑 ----

    /** 获取或创建维度数据 */
    private DimGeoData getOrCreateDim(ResourceKey<Level> dim) {
        String key = dim.toString().toString();
        return dimensions.computeIfAbsent(key, k -> new DimGeoData());
    }

    /** 合并多个图形组为一个 */
    private void mergeGroups(DimGeoData data, Set<String> gids, ChunkCoord newChunk) {
        Set<ChunkCoord> merged = new LinkedHashSet<>();
        for (String gid : gids) {
            Set<ChunkCoord> group = data.groups.remove(gid);
            if (group != null) merged.addAll(group);
        }
        merged.add(newChunk);

        String newGid = "g" + data.nextGroupId++;
        data.groups.put(newGid, merged);
        for (ChunkCoord c : merged) {
            data.chunkIndex.put(c, newGid);
        }
    }

    /** 拆分一个不连通的图形组为多个连通子组 */
    private void splitGroup(DimGeoData data, String oldGid, Set<ChunkCoord> remaining) {
        data.groups.remove(oldGid);

        // BFS 找所有连通分量
        Set<ChunkCoord> visited = new HashSet<>();
        List<Set<ChunkCoord>> components = new ArrayList<>();

        for (ChunkCoord start : remaining) {
            if (visited.contains(start)) continue;
            Set<ChunkCoord> component = new LinkedHashSet<>();
            Queue<ChunkCoord> queue = new LinkedList<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                ChunkCoord cur = queue.poll();
                component.add(cur);
                for (ChunkCoord nb : neighbors(cur)) {
                    if (remaining.contains(nb) && !visited.contains(nb)) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
            components.add(component);
        }

        // 为每个分量创建新图形
        for (Set<ChunkCoord> comp : components) {
            String newGid = "g" + data.nextGroupId++;
            data.groups.put(newGid, comp);
            for (ChunkCoord c : comp) {
                data.chunkIndex.put(c, newGid);
            }
        }
    }

    /** 检查区块集合是否连通 */
    private boolean isConnected(Set<ChunkCoord> chunks) {
        if (chunks.size() <= 1) return true;
        Iterator<ChunkCoord> it = chunks.iterator();
        ChunkCoord start = it.next();

        Set<ChunkCoord> visited = new HashSet<>();
        Queue<ChunkCoord> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            ChunkCoord cur = queue.poll();
            for (ChunkCoord nb : neighbors(cur)) {
                if (chunks.contains(nb) && !visited.contains(nb)) {
                    visited.add(nb);
                    queue.add(nb);
                }
            }
        }
        return visited.size() == chunks.size();
    }

    /** 获取4邻居 */
    private static Set<ChunkCoord> neighbors(ChunkCoord c) {
        Set<ChunkCoord> nbs = new HashSet<>();
        nbs.add(new ChunkCoord(c.x + 1, c.z));
        nbs.add(new ChunkCoord(c.x - 1, c.z));
        nbs.add(new ChunkCoord(c.x, c.z + 1));
        nbs.add(new ChunkCoord(c.x, c.z - 1));
        return nbs;
    }

    // ---- 持久化 ----

    private void save() {
        DimStore store = new DimStore();
        store.dimensions = new LinkedHashMap<>();
        for (var dimEntry : dimensions.entrySet()) {
            DimStore.DimensionEntry de = new DimStore.DimensionEntry();
            de.nextGroupId = dimEntry.getValue().nextGroupId;
            de.groups = new LinkedHashMap<>();
            for (var ge : dimEntry.getValue().groups.entrySet()) {
                DimStore.GroupEntry ge2 = new DimStore.GroupEntry();
                ge2.chunks = new ArrayList<>();
                for (ChunkCoord c : ge.getValue()) {
                    ge2.chunks.add(new int[]{c.x, c.z});
                }
                de.groups.put(ge.getKey(), ge2);
            }
            store.dimensions.put(dimEntry.getKey(), de);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[samsara] geometry.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);

            // 先尝试新格式（带 "dimensions" 字段）
            DimStore store = GSON.fromJson(json, DimStore.class);
            if (store == null) return;

            if (store.dimensions != null && !store.dimensions.isEmpty()) {
                // 新格式
                for (var dimEntry : store.dimensions.entrySet()) {
                    DimGeoData data = new DimGeoData();
                    DimStore.DimensionEntry de = dimEntry.getValue();
                    data.nextGroupId = de.nextGroupId;
                    if (de.groups != null) {
                        for (var ge : de.groups.entrySet()) {
                            Set<ChunkCoord> group = new LinkedHashSet<>();
                            if (ge.getValue().chunks != null) {
                                for (int[] arr : ge.getValue().chunks) {
                                    group.add(new ChunkCoord(arr[0], arr[1]));
                                }
                            }
                            data.groups.put(ge.getKey(), group);
                            for (ChunkCoord c : group) {
                                data.chunkIndex.put(c, ge.getKey());
                            }
                        }
                    }
                    dimensions.put(dimEntry.getKey(), data);
                }
                // 统计日志
                int totalGroups = 0, totalChunks = 0;
                for (DimGeoData d : dimensions.values()) {
                    totalGroups += d.groups.size();
                    totalChunks += d.chunkIndex.size();
                }
                Samsara.LOGGER.info("[samsara] 加载 geometry（按维度）: {} 个维度, {} 个图形, {} 个区块",
                        dimensions.size(), totalGroups, totalChunks);
            } else {
                // 旧格式兼容：尝试作为旧 GeoData 解析，全部归入 overworld
                OldGeoData old = GSON.fromJson(json, OldGeoData.class);
                if (old != null) {
                    DimGeoData data = new DimGeoData();
                    data.nextGroupId = old.nextGroupId;
                    if (old.groups != null) {
                        for (var ge : old.groups.entrySet()) {
                            Set<ChunkCoord> group = new LinkedHashSet<>();
                            if (ge.getValue().chunks != null) {
                                for (int[] arr : ge.getValue().chunks) {
                                    group.add(new ChunkCoord(arr[0], arr[1]));
                                }
                            }
                            data.groups.put(ge.getKey(), group);
                            for (ChunkCoord c : group) {
                                data.chunkIndex.put(c, ge.getKey());
                            }
                        }
                    }
                    dimensions.put(Level.OVERWORLD.toString(), data);
                    Samsara.LOGGER.info("[samsara] 加载 geometry（旧格式，全部归入 overworld）: {} 个图形, {} 个区块",
                            data.groups.size(), data.chunkIndex.size());
                }
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[samsara] geometry.json 解析失败: {}", e.getMessage());
        }
    }

    /** 强制保存（供外部调用） */
    public synchronized void flush() {
        save();
    }

    /** 数据文件绝对路径 */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    // ---- JSON 数据模型（新格式：按维度） ----

    static class DimStore {
        Map<String, DimensionEntry> dimensions = new LinkedHashMap<>();

        static class DimensionEntry {
            int nextGroupId = 1;
            Map<String, GroupEntry> groups = new LinkedHashMap<>();
        }

        static class GroupEntry {
            List<int[]> chunks = new ArrayList<>(); // [[x,z], ...]
        }
    }

    // ---- JSON 数据模型（旧格式，仅用于兼容读取） ----

    static class OldGeoData {
        int nextGroupId = 1;
        Map<String, OldGroupEntry> groups = new LinkedHashMap<>();

        static class OldGroupEntry {
            List<int[]> chunks = new ArrayList<>();
        }
    }
}
