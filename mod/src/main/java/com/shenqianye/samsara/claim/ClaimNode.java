package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旗帜圈地节点（设计文档 §6.2.1）。
 * <p>
 * 一个节点 = 一个已注册的区块，可包含多面圈地旗。
 * 第一面旗帜决定区块的扩展方向（extension），后续旗帜仅记录坐标。
 * 区块注销条件：该区块内所有旗帜均被破坏。
 */
public class ClaimNode {
    private final ChunkCoord location;
    private final String facing;
    private final List<PatternEntry> patterns;
    /** 旗帜主人 UUID（放置者） */
    private final UUID ownerUUID;
    /** 旗帜主人名称 */
    private final String ownerName;
    /** 该区块内所有旗帜的坐标列表（等幂性：多面旗帜共享同一注册） */
    private final List<BannerEntry> banners = new ArrayList<>();
    /** 旋转后的扩展（延迟计算，首次访问时构建） */
    private Extension rotatedExtension;
    /** 影响坐标集（延迟计算） */
    private List<ChunkCoord> influencedCoords;

    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns,
                     int blockX, int blockZ, int bannerY,
                     UUID ownerUUID, String ownerName) {
        this.location = location;
        this.facing = facing;
        this.patterns = new ArrayList<>(patterns);
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.banners.add(new BannerEntry(blockX, bannerY, blockZ, facing, new ArrayList<>(patterns)));
    }

    /** 兼容旧构造 */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns, int bannerY,
                     UUID ownerUUID, String ownerName) {
        this(location, facing, patterns, location.x * 16 + 8, location.z * 16 + 8, bannerY,
                ownerUUID, ownerName);
    }

    /** 兼容旧构造 */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns, int bannerY) {
        this(location, facing, patterns, bannerY, null, null);
    }

    /** 兼容旧构造 */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns) {
        this(location, facing, patterns, 0, null, null);
    }

    public ChunkCoord getLocation() { return location; }
    public String getFacing() { return facing; }
    public List<PatternEntry> getPatterns() { return patterns; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public List<BannerEntry> getBanners() { return banners; }

    /** 第一面旗帜的 X 坐标（兼容旧接口） */
    public int getBlockX() { return banners.isEmpty() ? location.x * 16 + 8 : banners.get(0).blockX(); }
    /** 第一面旗帜的 Z 坐标（兼容旧接口） */
    public int getBlockZ() { return banners.isEmpty() ? location.z * 16 + 8 : banners.get(0).blockZ(); }
    /** 第一面旗帜的 Y 坐标（兼容旧接口） */
    public int getBannerY() { return banners.isEmpty() ? 0 : banners.get(0).blockY(); }

    /** 添加一面旗帜到该区块（等幂性：同坐标+同方向+同类型才跳过） */
    public void addBanner(int blockX, int blockY, int blockZ, String facing, List<PatternEntry> patterns) {
        BannerEntry newEntry = new BannerEntry(blockX, blockY, blockZ, facing, new ArrayList<>(patterns));
        // 去重：同坐标+同方向+同类型才跳过
        for (BannerEntry be : banners) {
            if (be.blockX == blockX && be.blockY == blockY && be.blockZ == blockZ
                    && be.sameConfig(newEntry)) {
                return;
            }
        }
        banners.add(newEntry);
    }

    /**
     * 移除指定坐标的旗帜。
     *
     * @return 移除后该区块是否还有旗帜（true = 继续保留注册，false = 应注销）
     */
    public boolean removeBanner(int blockX, int blockY, int blockZ) {
        banners.removeIf(be -> be.blockX == blockX && be.blockY == blockY && be.blockZ == blockZ);
        return !banners.isEmpty();
    }

    /** 获取旋转后的扩展方向集（根据旗帜朝向旋转） */
    public Extension getExtension() {
        if (rotatedExtension == null) {
            rotatedExtension = new Extension();
            for (PatternEntry pe : patterns) {
                rotatedExtension.addDirection(pe.patternId(), pe.color());
            }
            rotatedExtension.rotate(facing);
        }
        return rotatedExtension;
    }

    /** 计算影响坐标集（从节点位置沿4个正方向延伸） */
    public List<ChunkCoord> getInfluencedCoords() {
        if (influencedCoords != null) return influencedCoords;
        influencedCoords = new ArrayList<>();
        Extension ext = getExtension();
        addLine(location, 0, -ext.getUp(), influencedCoords);        // 前
        addLine(location, 0, ext.getDown(), influencedCoords);        // 后
        addLine(location, -ext.getLeft(), 0, influencedCoords);       // 左
        addLine(location, ext.getRight(), 0, influencedCoords);       // 右
        return influencedCoords;
    }

    /** 从起点沿方向延伸 N 格，生成所有中间格点 */
    private static void addLine(ChunkCoord start, int dx, int dz,
                                 List<ChunkCoord> out) {
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist == 0) return;
        int sx = dx / dist;
        int sz = dz / dist;
        for (int i = 1; i <= dist; i++) {
            out.add(new ChunkCoord(start.x + sx * i, start.z + sz * i));
        }
    }

    /** 旗帜图案记录（patternId + color） */
    public record PatternEntry(String patternId, int color) {}

    /** 区块内单面旗帜的记录（含方向和类型） */
    public record BannerEntry(int blockX, int blockY, int blockZ,
                               String facing, List<PatternEntry> patterns) {
        /** 判断两面旗帜是否同方向同类型 */
        public boolean sameConfig(BannerEntry other) {
            if (!this.facing.equals(other.facing)) return false;
            if (this.patterns.size() != other.patterns.size()) return false;
            for (int i = 0; i < this.patterns.size(); i++) {
                PatternEntry a = this.patterns.get(i);
                PatternEntry b = other.patterns.get(i);
                if (!a.patternId().equals(b.patternId()) || a.color() != b.color()) return false;
            }
            return true;
        }
    }
}
