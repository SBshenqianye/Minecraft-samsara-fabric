package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.geometry.Geometry;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旗帜圈地服务（设计文档 §6.4 AreaService，单例）。
 * <p>
 * 管理所有已注册的旗帜节点，计算白名单区块集（使用 {@link Geometry}），
 * 提供保护判定（O(1) 缓存查询）。
 * <p>
 * 数据持久化至 {@code config/samsara/claims.json}。
 */
public class AreaService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AreaService instance;
    private final Path file;

    /** 所有已注册的旗帜节点（区块坐标 → 节点） */
    private final Map<ChunkCoord, ClaimNode> nodes = new ConcurrentHashMap<>();
    /** 计算后的白名单缓存（区块坐标 → true） */
    private volatile Set<ChunkCoord> whitelist = Set.of();
    /** 是否需要重算 */
    private volatile boolean dirty = true;

    private AreaService() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("claims.json");
        load();
    }

    public static synchronized AreaService getInstance() {
        if (instance == null) {
            instance = new AreaService();
        }
        return instance;
    }

    // ---- 节点管理 ----

    /** 注册一个旗帜节点（放置旗帜时调用） */
    public synchronized String addNode(ClaimNode node) {
        ChunkCoord loc = node.getLocation();
        if (nodes.containsKey(loc)) {
            return "该位置已有圈地旗帜（先 remove 再添加）";
        }
        nodes.put(loc, node);
        dirty = true;
        save();
        return "已注册圈地旗 " + loc + "（朝向 " + node.getFacing()
                + "，共 " + nodes.size() + " 面旗帜）";
    }

    /** 移除一个旗帜节点（拆除旗帜或手动移除时调用） */
    public synchronized String removeNode(ChunkCoord loc) {
        if (nodes.remove(loc) != null) {
            dirty = true;
            save();
            return "已移除圈地旗 " + loc + "（剩余 " + nodes.size() + " 面）";
        }
        return "未找到该位置的圈地旗";
    }

    /** 获取指定位置的节点（无则 null） */
    public ClaimNode getNode(ChunkCoord loc) {
        return nodes.get(loc);
    }

    /** 获取所有节点（不可变视图） */
    public Map<ChunkCoord, ClaimNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    // ---- 白名单计算 ----

    /**
     * 重算白名单（调用化线为面算法）。惰性：仅 dirty 时重算。
     */
    public Set<ChunkCoord> getWhitelist() {
        if (dirty || whitelist.isEmpty()) {
            recompute();
        }
        return whitelist;
    }

    /** 强制重算白名单 */
    public synchronized void recompute() {
        if (nodes.isEmpty()) {
            whitelist = Set.of();
            dirty = false;
            return;
        }

        // 1. 收集所有节点坐标
        Set<ChunkCoord> nodeSet = new HashSet<>(nodes.keySet());

        // 2. 构建线段：每个节点 → 每个影响坐标
        List<int[]> segments = new ArrayList<>();
        for (ClaimNode node : nodes.values()) {
            ChunkCoord loc = node.getLocation();
            for (ChunkCoord inf : node.getInfluencedCoords()) {
                segments.add(new int[]{loc.x, loc.z, inf.x, inf.z});
            }
        }

        // 3. 调用化线为面算法
        whitelist = Geometry.compute(nodeSet, segments);
        dirty = false;

        Samsara.LOGGER.info("[周目管理 samsara] 白名单重算完成：{} 个节点 → {} 个保护区块",
                nodes.size(), whitelist.size());
    }

    /** 判定区块是否受保护 */
    public boolean isProtected(int chunkX, int chunkZ) {
        return getWhitelist().contains(new ChunkCoord(chunkX, chunkZ));
    }

    // ---- 持久化 ----

    private void save() {
        ClaimsData data = new ClaimsData();
        for (Map.Entry<ChunkCoord, ClaimNode> e : nodes.entrySet()) {
            ClaimNode n = e.getValue();
            ClaimsData.NodeEntry ne = new ClaimsData.NodeEntry();
            ne.x = n.getLocation().x;
            ne.z = n.getLocation().z;
            ne.facing = n.getFacing();
            ne.patterns = new ArrayList<>();
            for (ClaimNode.PatternEntry pe : n.getPatterns()) {
                ClaimsData.PatternEntry pe2 = new ClaimsData.PatternEntry();
                pe2.id = pe.patternId();
                pe2.color = pe.color();
                ne.patterns.add(pe2);
            }
            data.nodes.add(ne);
        }
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] claims.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            ClaimsData data = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8), ClaimsData.class);
            if (data == null || data.nodes == null) return;
            for (ClaimsData.NodeEntry ne : data.nodes) {
                List<ClaimNode.PatternEntry> pes = new ArrayList<>();
                if (ne.patterns != null) {
                    for (ClaimsData.PatternEntry pe : ne.patterns) {
                        pes.add(new ClaimNode.PatternEntry(pe.id, pe.color));
                    }
                }
                ClaimNode node = new ClaimNode(
                        new ChunkCoord(ne.x, ne.z), ne.facing, pes);
                nodes.put(node.getLocation(), node);
            }
            dirty = true;
            Samsara.LOGGER.info("[周目管理 samsara] 加载 {} 个圈地旗", nodes.size());
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] claims.json 解析失败: {}", e.getMessage());
        }
    }

    /** 数据文件绝对路径 */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    // ---- JSON 数据模型 ----

    static class ClaimsData {
        List<NodeEntry> nodes = new ArrayList<>();

        static class NodeEntry {
            int x, z;
            String facing;
            List<PatternEntry> patterns = new ArrayList<>();
        }

        static class PatternEntry {
            String id;
            int color;
        }
    }
}
