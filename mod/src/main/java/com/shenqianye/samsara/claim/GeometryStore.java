package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 几何图形存储（设计文档 §6.4 GeometryStore）。
 * <p>
 * 将已注册区块按连通性分组为"几何图形"（geometry group），每组是一个连通区域。
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

    /** 图形组 ID → 组内区块集合 */
    private final Map<String, Set<ChunkCoord>> groups = new LinkedHashMap<>();
    /** 区块 → 所属图形组 ID（索引，加速查找） */
    private final Map<ChunkCoord, String> chunkIndex = new ConcurrentHashMap<>();
    /** 下一个可用组 ID */
    private int nextGroupId = 1;

    private GeometryStore() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try { Files.createDirectories(dir); } catch (IOException e) { throw new RuntimeException(e); }
        file = dir.resolve("geometry.json");
        load();
    }

    public static synchronized GeometryStore getInstance() {
        if (instance == null) instance = new GeometryStore();
        return instance;
    }

    // ---- 公共 API ----

    /** 获取所有受保护区块（白名单） */
    public Set<ChunkCoord> getWhitelist() {
        return new HashSet<>(chunkIndex.keySet());
    }

    /** 判断区块是否受保护 */
    public boolean isProtected(int chunkX, int chunkZ) {
        return chunkIndex.containsKey(new ChunkCoord(chunkX, chunkZ));
    }

    /**
     * 添加一个区块到几何图形（放置旗帜时调用）。
     * 检查4邻居，合并到已有图形或创建新图形。
     */
    public synchronized void addChunk(ChunkCoord chunk) {
        if (chunkIndex.containsKey(chunk)) return; // 已存在

        // 查找4邻居所属的图形组
        Set<String> neighborGroups = new LinkedHashSet<>();
        for (ChunkCoord nb : neighbors(chunk)) {
            String gid = chunkIndex.get(nb);
            if (gid != null) neighborGroups.add(gid);
        }

        if (neighborGroups.isEmpty()) {
            // 无邻居 → 创建新图形
            String gid = "g" + nextGroupId++;
            Set<ChunkCoord> group = new LinkedHashSet<>();
            group.add(chunk);
            groups.put(gid, group);
            chunkIndex.put(chunk, gid);
        } else if (neighborGroups.size() == 1) {
            // 1个邻居 → 加入该图形
            String gid = neighborGroups.iterator().next();
            groups.get(gid).add(chunk);
            chunkIndex.put(chunk, gid);
        } else {
            // 多个邻居 → 合并所有图形为一个
            mergeGroups(neighborGroups, chunk);
        }
    }

    /**
     * 从几何图形移除一个区块（拆除旗帜时调用）。
     * 移除后检查连通性，必要时拆分图形。
     */
    public synchronized void removeChunk(ChunkCoord chunk) {
        String gid = chunkIndex.remove(chunk);
        if (gid == null) return;

        Set<ChunkCoord> group = groups.get(gid);
        if (group == null) return;
        group.remove(chunk);

        if (group.isEmpty()) {
            groups.remove(gid);
            return;
        }

        // 检查连通性：从组内任意区块 BFS，看是否能访问所有区块
        if (isConnected(group)) return;

        // 不连通 → 拆分
        splitGroup(gid, group);
    }

    /** 获取所有图形组（只读视图，供调试/显示） */
    public Map<String, Set<ChunkCoord>> getGroups() {
        return Collections.unmodifiableMap(groups);
    }

    /** 清空所有图形（供 AreaService.rebuildGeometry 使用） */
    public synchronized void clear() {
        groups.clear();
        chunkIndex.clear();
        nextGroupId = 1;
    }

    // ---- 内部逻辑 ----

    /** 合并多个图形组为一个 */
    private void mergeGroups(Set<String> gids, ChunkCoord newChunk) {
        Set<ChunkCoord> merged = new LinkedHashSet<>();
        for (String gid : gids) {
            Set<ChunkCoord> group = groups.remove(gid);
            if (group != null) merged.addAll(group);
        }
        merged.add(newChunk);

        String newGid = "g" + nextGroupId++;
        groups.put(newGid, merged);
        for (ChunkCoord c : merged) {
            chunkIndex.put(c, newGid);
        }
    }

    /** 拆分一个不连通的图形组为多个连通子组 */
    private void splitGroup(String oldGid, Set<ChunkCoord> remaining) {
        groups.remove(oldGid);

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
            String newGid = "g" + nextGroupId++;
            groups.put(newGid, comp);
            for (ChunkCoord c : comp) {
                chunkIndex.put(c, newGid);
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
        GeoData data = new GeoData();
        data.nextGroupId = nextGroupId;
        data.groups = new LinkedHashMap<>();
        for (var e : groups.entrySet()) {
            GeoData.GroupEntry ge = new GeoData.GroupEntry();
            ge.chunks = new ArrayList<>();
            for (ChunkCoord c : e.getValue()) {
                ge.chunks.add(new int[]{c.x, c.z});
            }
            data.groups.put(e.getKey(), ge);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[samsara] geometry.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            GeoData data = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8), GeoData.class);
            if (data == null) return;
            nextGroupId = data.nextGroupId;
            if (data.groups != null) {
                for (var e : data.groups.entrySet()) {
                    Set<ChunkCoord> group = new LinkedHashSet<>();
                    if (e.getValue().chunks != null) {
                        for (int[] arr : e.getValue().chunks) {
                            group.add(new ChunkCoord(arr[0], arr[1]));
                        }
                    }
                    groups.put(e.getKey(), group);
                    for (ChunkCoord c : group) {
                        chunkIndex.put(c, e.getKey());
                    }
                }
            }
            Samsara.LOGGER.info("[samsara] 加载 geometry: {} 个图形, {} 个区块",
                    groups.size(), chunkIndex.size());
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

    // ---- JSON 数据模型 ----

    static class GeoData {
        int nextGroupId = 1;
        Map<String, GroupEntry> groups = new LinkedHashMap<>();

        static class GroupEntry {
            List<int[]> chunks = new ArrayList<>(); // [[x,z], ...]
        }
    }
}
