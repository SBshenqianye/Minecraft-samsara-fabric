package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旗帜圈地服务（设计文档 §6.4 AreaService，单例）。
 * <p>
 * 管理所有已注册的旗帜节点，按维度独立存储。
 * 每个维度有独立的白名单和保护区域。
 * <p>
 * 数据持久化至 {@code config/samsara/claims.json}。
 */
public class AreaService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AreaService instance;
    private final Path file;

    /** 按维度存储的旗帜节点：维度 → (区块坐标 → 节点) */
    private final Map<ResourceKey<Level>, Map<ChunkCoord, ClaimNode>> nodes = new ConcurrentHashMap<>();

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

    /** 获取指定维度的节点 map */
    private Map<ChunkCoord, ClaimNode> dimNodes(ResourceKey<Level> dim) {
        return nodes.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
    }

    // ---- 节点管理 ----

    /**
     * 注册旗帜（放置旗帜时调用）。
     * 等幂性：同维度+同区块+同坐标+同方向+同类型才跳过。
     */
    public synchronized String addNode(ClaimNode newNode) {
        ResourceKey<Level> dim = newNode.getDimension();
        ChunkCoord loc = newNode.getLocation();
        ClaimNode existing = dimNodes(dim).get(loc);

        if (existing != null) {
            existing.addBanner(newNode.getBlockX(), newNode.getBannerY(), newNode.getBlockZ(),
                    newNode.getFacing(), newNode.getPatterns());
            save();
            if (com.shenqianye.samsara.DebugToggle.isDebugEnabled()) {
                return "该区块已有圈地旗帜，添加坐标记录（" + dim.toString()
                        + " " + loc + "，共 " + existing.getBanners().size() + " 面旗）";
            }
            return null;
        }

        dimNodes(dim).put(loc, newNode);

        // 增量更新几何图形
        GeometryStore geo = GeometryStore.getInstance();
        for (ChunkCoord inf : newNode.getInfluencedCoords()) {
            geo.addChunk(dim, inf);
        }
        geo.addChunk(dim, loc);
        geo.flush();

        save();
        return "已注册圈地旗 " + dim.toString() + " " + loc + "（朝向 " + newNode.getFacing()
                + "，共 " + dimNodes(dim).size() + " 面旗帜）";
    }

    /**
     * 移除指定坐标的旗帜。
     */
    public synchronized String removeBanner(ResourceKey<Level> dim, ChunkCoord chunkLoc,
                                             int blockX, int blockY, int blockZ) {
        ClaimNode node = dimNodes(dim).get(chunkLoc);
        if (node == null) return null;

        boolean hasRemaining = node.removeBanner(blockX, blockY, blockZ);
        if (hasRemaining) {
            save();
            if (com.shenqianye.samsara.DebugToggle.isDebugEnabled()) {
                return "移除旗帜坐标，区块 " + chunkLoc + " 还有 "
                        + node.getBanners().size() + " 面旗";
            }
            return null;
        }

        dimNodes(dim).remove(chunkLoc);
        rebuildGeometry(dim);
        save();
        return "已移除圈地旗 " + dim.toString() + " " + chunkLoc
                + "（剩余 " + dimNodes(dim).size() + " 面）";
    }

    /** 获取指定维度+位置的节点 */
    public ClaimNode getNode(ResourceKey<Level> dim, ChunkCoord loc) {
        return dimNodes(dim).get(loc);
    }

    /** 获取指定维度的所有节点 */
    public Map<ChunkCoord, ClaimNode> getNodes(ResourceKey<Level> dim) {
        return Collections.unmodifiableMap(dimNodes(dim));
    }

    /** 获取所有维度的所有节点（扁平化视图，供调试/列表使用） */
    public Map<ResourceKey<Level>, Map<ChunkCoord, ClaimNode>> getAllNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /** 移除整个区块的注册（debug 用） */
    public synchronized String removeNode(ResourceKey<Level> dim, ChunkCoord loc) {
        if (dimNodes(dim).remove(loc) != null) {
            rebuildGeometry(dim);
            save();
            return "已移除圈地旗 " + dim.toString() + " " + loc
                    + "（剩余 " + dimNodes(dim).size() + " 面）";
        }
        return "未找到该位置的圈地旗";
    }

    // ---- 白名单计算 ----

    /** 获取指定维度的白名单 */
    public Set<ChunkCoord> getWhitelist(ResourceKey<Level> dim) {
        return GeometryStore.getInstance().getWhitelist(dim);
    }

    /**
     * 构建"region 文件 → 白名单区块数"索引。
     * <p>
     * 用法：删除时查此索引，region 文件内白名单数=0 → 整文件秒删（不读 header）。
     * 白名单数>0 → 读 header 只清零非白名单 slot。
     *
     * @param dim 指定维度
     * @return key=region 文件名（如 "r.0.0.mca"），value=该文件内白名单区块数
     */
    public Map<String, Integer> getWhitelistCountByRegionFile(ResourceKey<Level> dim) {
        Set<ChunkCoord> whitelist = getWhitelist(dim);
        Map<String, Integer> counts = new HashMap<>();
        for (ChunkCoord cc : whitelist) {
            int rx = cc.x >> 5;  // ÷32
            int rz = cc.z >> 5;
            String name = "r." + rx + "." + rz + ".mca";
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    /** 获取所有维度的白名单总数 */
    public int getTotalWhitelistSize() {
        int total = 0;
        for (ResourceKey<Level> dim : nodes.keySet()) {
            total += GeometryStore.getInstance().getWhitelist(dim).size();
        }
        return total;
    }

    /** 强制重算指定维度的白名单 */
    public synchronized void recompute(ResourceKey<Level> dim) {
        rebuildGeometry(dim);
        Samsara.LOGGER.info("[周目管理 samsara] {} 白名单重算完成：{} 个节点 → {} 个保护区块",
                dim.toString(), dimNodes(dim).size(),
                GeometryStore.getInstance().getWhitelist(dim).size());
    }

    /** 判定指定维度的区块是否受保护 */
    public boolean isProtected(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return GeometryStore.getInstance().isProtected(dim, chunkX, chunkZ);
    }

    /** 获取指定维度所有注册旗帜的平均 Y 坐标 */
    public double getAvgBannerY(ResourceKey<Level> dim) {
        Map<ChunkCoord, ClaimNode> dimMap = dimNodes(dim);
        if (dimMap.isEmpty()) return 0;
        long total = 0;
        for (ClaimNode n : dimMap.values()) {
            total += n.getBannerY();
        }
        return (double) total / dimMap.size();
    }

    // ---- 内部方法 ----

    /** 从指定维度的所有节点重建几何图形 */
    private void rebuildGeometry(ResourceKey<Level> dim) {
        GeometryStore geo = GeometryStore.getInstance();
        geo.clear(dim);
        for (ClaimNode node : dimNodes(dim).values()) {
            for (ChunkCoord inf : node.getInfluencedCoords()) {
                geo.addChunk(dim, inf);
            }
            geo.addChunk(dim, node.getLocation());
        }
        geo.flush();
    }

    // ---- 持久化 ----

    private void save() {
        ClaimsData data = new ClaimsData();
        for (var dimEntry : nodes.entrySet()) {
            String dimKey = dimEntry.getKey().toString();
            for (Map.Entry<ChunkCoord, ClaimNode> e : dimEntry.getValue().entrySet()) {
                ClaimNode n = e.getValue();
                ClaimsData.NodeEntry ne = new ClaimsData.NodeEntry();
                ne.dimension = dimKey;
                ne.x = n.getLocation().x;
                ne.z = n.getLocation().z;
                ne.y = n.getBannerY();
                ne.facing = n.getFacing();
                if (n.getOwnerUUID() != null) ne.ownerUUID = n.getOwnerUUID().toString();
                if (n.getOwnerName() != null) ne.ownerName = n.getOwnerName();
                ne.patterns = new ArrayList<>();
                for (ClaimNode.PatternEntry pe : n.getPatterns()) {
                    ClaimsData.PatternEntry pe2 = new ClaimsData.PatternEntry();
                    pe2.id = pe.patternId();
                    pe2.color = pe.color();
                    ne.patterns.add(pe2);
                }
                ne.banners = new ArrayList<>();
                for (ClaimNode.BannerEntry be : n.getBanners()) {
                    ClaimsData.BannerEntry be2 = new ClaimsData.BannerEntry();
                    be2.blockX = be.blockX();
                    be2.blockY = be.blockY();
                    be2.blockZ = be.blockZ();
                    be2.facing = be.facing();
                    be2.patterns = new ArrayList<>();
                    for (ClaimNode.PatternEntry pe : be.patterns()) {
                        ClaimsData.PatternEntry pe2 = new ClaimsData.PatternEntry();
                        pe2.id = pe.patternId();
                        pe2.color = pe.color();
                        be2.patterns.add(pe2);
                    }
                    ne.banners.add(be2);
                }
                data.nodes.add(ne);
            }
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
                // 维度：兼容旧数据（无 dimension 字段 → overworld）
                ResourceKey<Level> dim = Level.OVERWORLD;
                if (ne.dimension != null && !ne.dimension.isEmpty()) {
                    dim = dimensionFromString(ne.dimension);
                }

                List<ClaimNode.PatternEntry> pes = new ArrayList<>();
                if (ne.patterns != null) {
                    for (ClaimsData.PatternEntry pe : ne.patterns) {
                        pes.add(new ClaimNode.PatternEntry(pe.id, pe.color));
                    }
                }
                UUID ownerUUID = null;
                if (ne.ownerUUID != null && !ne.ownerUUID.isEmpty()) {
                    try { ownerUUID = UUID.fromString(ne.ownerUUID); } catch (Exception ignored) {}
                }
                int bx, bz, by;
                if (ne.banners != null && !ne.banners.isEmpty()) {
                    ClaimsData.BannerEntry first = ne.banners.get(0);
                    bx = first.blockX;
                    bz = first.blockZ;
                    by = first.blockY;
                } else {
                    bx = ne.x * 16 + 8;
                    bz = ne.z * 16 + 8;
                    by = ne.y;
                }
                ClaimNode node = new ClaimNode(
                        new ChunkCoord(ne.x, ne.z), dim, ne.facing, pes,
                        bx, bz, by,
                        ownerUUID, ne.ownerName);
                if (ne.banners != null && ne.banners.size() > 1) {
                    for (int i = 1; i < ne.banners.size(); i++) {
                        ClaimsData.BannerEntry be = ne.banners.get(i);
                        String beFacing = be.facing != null ? be.facing : ne.facing;
                        List<ClaimNode.PatternEntry> bePatterns = pes;
                        if (be.patterns != null && !be.patterns.isEmpty()) {
                            bePatterns = new ArrayList<>();
                            for (ClaimsData.PatternEntry pe : be.patterns) {
                                bePatterns.add(new ClaimNode.PatternEntry(pe.id, pe.color));
                            }
                        }
                        node.addBanner(be.blockX, be.blockY, be.blockZ, beFacing, bePatterns);
                    }
                }
                dimNodes(dim).put(node.getLocation(), node);
            }
            // 重建所有维度的几何图形
            for (ResourceKey<Level> dim : nodes.keySet()) {
                rebuildGeometry(dim);
            }
            int totalNodes = nodes.values().stream().mapToInt(Map::size).sum();
            Samsara.LOGGER.info("[周目管理 samsara] 加载 {} 个圈地旗（{} 个维度）",
                    totalNodes, nodes.size());
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] claims.json 解析失败: {}", e.getMessage());
        }
    }

    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    /** 维度字符串 → ResourceKey 映射（运行时通过反射发现所有 ResourceKey<Level> 字段） */
    private static final Map<String, ResourceKey<Level>> DIMENSION_MAP = new HashMap<>();
    static {
        DIMENSION_MAP.put(Level.OVERWORLD.toString(), Level.OVERWORLD);
        // MC 26.2 Mojang 映射中 Level 没有 NETHER/END 静态字段，通过反射扫描所有 ResourceKey 字段
        try {
            for (var field : Level.class.getDeclaredFields()) {
                if (field.getType() == ResourceKey.class) {
                    try {
                        var val = field.get(null);
                        if (val instanceof ResourceKey<?> rk) {
                            DIMENSION_MAP.put(rk.toString(), (ResourceKey<Level>) rk);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Samsara.LOGGER.debug("[周目管理 samsara] 反射扫描 Level ResourceKey 字段失败: {}", e.getMessage());
        }
    }

    private static ResourceKey<Level> dimensionFromString(String s) {
        ResourceKey<Level> key = DIMENSION_MAP.get(s);
        if (key != null) return key;
        // 未知维度 → 默认 overworld
        Samsara.LOGGER.warn("[周目管理 samsara] 未知维度 '{}'，归入 overworld", s);
        return Level.OVERWORLD;
    }

    // ---- JSON 数据模型 ----

    static class ClaimsData {
        List<NodeEntry> nodes = new ArrayList<>();

        static class NodeEntry {
            String dimension; // "minecraft:overworld" 等
            int x, z, y;
            String facing;
            String ownerUUID;
            String ownerName;
            List<PatternEntry> patterns = new ArrayList<>();
            List<BannerEntry> banners = new ArrayList<>();
        }

        static class PatternEntry {
            String id;
            int color;
        }

        static class BannerEntry {
            int blockX, blockY, blockZ;
            String facing;
            List<PatternEntry> patterns;
        }
    }
}
