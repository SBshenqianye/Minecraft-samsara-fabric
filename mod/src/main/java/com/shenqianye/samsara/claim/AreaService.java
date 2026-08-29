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
 * 旗帜圈地服务（设计文档 §6.4 AreaService，单例）。
 * <p>
 * 管理所有已注册的旗帜节点，计算白名单区块集（使用 {@link GeometryStore}），
 * 提供保护判定（O(1) 缓存查询）。
 * <p>
 * 等幂性：同一区块多次放置旗帜仅添加坐标记录，不重复注册。
 * 区块注销条件：该区块内所有旗帜均被破坏。
 * <p>
 * 数据持久化至 {@code config/samsara/claims.json}。
 */
public class AreaService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AreaService instance;
    private final Path file;

    /** 所有已注册的旗帜节点（区块坐标 → 节点，每个区块一个节点） */
    private final Map<ChunkCoord, ClaimNode> nodes = new ConcurrentHashMap<>();

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

    /**
     * 注册旗帜（放置旗帜时调用）。
     * <p>
     * 等幂性：若该区块已有注册，仅添加坐标记录，不重复注册。
     *
     * @return 注册结果消息
     */
    public synchronized String addNode(ClaimNode newNode) {
        ChunkCoord loc = newNode.getLocation();
        ClaimNode existing = nodes.get(loc);

        if (existing != null) {
            // 区块已注册：添加旗帜坐标（等幂性：同坐标+同方向+同类型才跳过）
            existing.addBanner(newNode.getBlockX(), newNode.getBannerY(), newNode.getBlockZ(),
                    newNode.getFacing(), newNode.getPatterns());
            save();
            if (com.shenqianye.samsara.DebugToggle.isDebugEnabled()) {
                return "该区块已有圈地旗帜，添加坐标记录（区块 " + loc
                        + "，共 " + existing.getBanners().size() + " 面旗）";
            }
            return null; // 非 debug 模式静默跳过
        }

        // 新区块注册
        nodes.put(loc, newNode);

        // 增量更新几何图形
        GeometryStore geo = GeometryStore.getInstance();
        for (ChunkCoord inf : newNode.getInfluencedCoords()) {
            geo.addChunk(inf);
        }
        geo.addChunk(loc);
        geo.flush();

        save();
        return "已注册圈地旗 " + loc + "（朝向 " + newNode.getFacing()
                + "，共 " + nodes.size() + " 面旗帜）";
    }

    /**
     * 移除指定坐标的旗帜。
     * <p>
     * 若该区块还有其他旗帜，仅移除坐标记录；否则注销整个区块。
     *
     * @return 移除结果消息
     */
    public synchronized String removeBanner(ChunkCoord chunkLoc, int blockX, int blockY, int blockZ) {
        ClaimNode node = nodes.get(chunkLoc);
        if (node == null) return null;

        boolean hasRemaining = node.removeBanner(blockX, blockY, blockZ);
        if (hasRemaining) {
            // 还有其他旗帜，保留注册
            save();
            if (com.shenqianye.samsara.DebugToggle.isDebugEnabled()) {
                return "移除旗帜坐标，区块 " + chunkLoc + " 还有 "
                        + node.getBanners().size() + " 面旗";
            }
            return null;
        }

        // 所有旗帜均已破坏，注销区块
        nodes.remove(chunkLoc);
        rebuildGeometry();
        save();
        return "已移除圈地旗 " + chunkLoc + "（剩余 " + nodes.size() + " 面）";
    }

    /** 获取指定位置的节点（无则 null） */
    public ClaimNode getNode(ChunkCoord loc) {
        return nodes.get(loc);
    }

    /** 获取所有节点（不可变视图） */
    public Map<ChunkCoord, ClaimNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /** 移除整个区块的注册（debug 用） */
    public synchronized String removeNode(ChunkCoord loc) {
        if (nodes.remove(loc) != null) {
            rebuildGeometry();
            save();
            return "已移除圈地旗 " + loc + "（剩余 " + nodes.size() + " 面）";
        }
        return "未找到该位置的圈地旗";
    }

    // ---- 白名单计算 ----

    /** 获取白名单（从 GeometryStore 读取，O(1) 查询） */
    public Set<ChunkCoord> getWhitelist() {
        return GeometryStore.getInstance().getWhitelist();
    }

    /** 强制重算白名单（重建几何图形） */
    public synchronized void recompute() {
        rebuildGeometry();
        Samsara.LOGGER.info("[周目管理 samsara] 白名单重算完成：{} 个节点 → {} 个保护区块",
                nodes.size(), getWhitelist().size());
    }

    /** 判定区块是否受保护 */
    public boolean isProtected(int chunkX, int chunkZ) {
        return GeometryStore.getInstance().isProtected(chunkX, chunkZ);
    }

    /** 获取所有注册旗帜的平均 Y 坐标（用于边界粒子在旗帜高度显示） */
    public double getAvgBannerY() {
        if (nodes.isEmpty()) return 0;
        long total = 0;
        for (ClaimNode n : nodes.values()) {
            total += n.getBannerY();
        }
        return (double) total / nodes.size();
    }

    // ---- 内部方法 ----

    /** 从所有节点重建几何图形 */
    private void rebuildGeometry() {
        GeometryStore geo = GeometryStore.getInstance();
        geo.clear();
        for (ClaimNode node : nodes.values()) {
            for (ChunkCoord inf : node.getInfluencedCoords()) {
                geo.addChunk(inf);
            }
            geo.addChunk(node.getLocation());
        }
        geo.flush();
    }

    // ---- 持久化 ----

    private void save() {
        ClaimsData data = new ClaimsData();
        for (Map.Entry<ChunkCoord, ClaimNode> e : nodes.entrySet()) {
            ClaimNode n = e.getValue();
            ClaimsData.NodeEntry ne = new ClaimsData.NodeEntry();
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
            // 保存所有旗帜坐标
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
                java.util.UUID ownerUUID = null;
                if (ne.ownerUUID != null && !ne.ownerUUID.isEmpty()) {
                    try { ownerUUID = java.util.UUID.fromString(ne.ownerUUID); } catch (Exception ignored) {}
                }
                // 兼容旧数据：如果没有 banners 字段，用第一个旗帜的坐标
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
                        new ChunkCoord(ne.x, ne.z), ne.facing, pes,
                        bx, bz, by,
                        ownerUUID, ne.ownerName);
                // 加载额外的旗帜坐标
                if (ne.banners != null && ne.banners.size() > 1) {
                    for (int i = 1; i < ne.banners.size(); i++) {
                        ClaimsData.BannerEntry be = ne.banners.get(i);
                        // 兼容旧数据：如果没有 facing/patterns，使用节点级默认值
                        String beFacing = be.facing != null ? be.facing : ne.facing;
                        List<ClaimNode.PatternEntry> bePatterns = pes; // 默认使用节点级图案
                        if (be.patterns != null && !be.patterns.isEmpty()) {
                            bePatterns = new ArrayList<>();
                            for (ClaimsData.PatternEntry pe : be.patterns) {
                                bePatterns.add(new ClaimNode.PatternEntry(pe.id, pe.color));
                            }
                        }
                        node.addBanner(be.blockX, be.blockY, be.blockZ, beFacing, bePatterns);
                    }
                }
                nodes.put(node.getLocation(), node);
            }
            rebuildGeometry();
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
