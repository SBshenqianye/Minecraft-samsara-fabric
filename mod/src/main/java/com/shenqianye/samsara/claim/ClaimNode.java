package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旗帜圈地节点（设计文档 §6.2.1）。
 * <p>
 * 一个节点 = 一个已注册的区块，可包含多面圈地旗。
 * 第一面旗帜决定区块的扩展方向（extension），后续旗帜仅记录坐标。
 * 区块注销条件：该区块内所有旗帜均被破坏。
 * <p>
 * 每个节点属于一个维度（overworld/nether/end），不同维度独立保护。
 */
public class ClaimNode {
    private final ChunkCoord location;
    private final ResourceKey<Level> dimension;
    private final String facing;
    private final List<PatternEntry> patterns;
    /** 旗帜主人 UUID（放置者实际 UUID；读取时按 identityOf 聚合） */
    private UUID ownerUUID;
    /** 旗帜主人名称 */
    private String ownerName;
    /** 该区块内所有旗帜的坐标列表（等幂性：多面旗帜共享同一注册） */
    private final List<BannerEntry> banners = new ArrayList<>();
    /** 旋转后的扩展（延迟计算，首次访问时构建） */
    private Extension rotatedExtension;
    /** 单面/多面旗帜的射线线段（延迟计算，不含任何填充面） */
    private List<int[]> claimSegments;
    /** 影响坐标集（延迟计算；只含射线格，不含填充面） */
    private List<ChunkCoord> influencedCoords;

    public ClaimNode(ChunkCoord location, ResourceKey<Level> dimension, String facing, List<PatternEntry> patterns,
                     int blockX, int blockZ, int bannerY,
                     UUID ownerUUID, String ownerName) {
        this.location = location;
        this.dimension = dimension;
        this.facing = facing;
        this.patterns = new ArrayList<>(patterns);
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.banners.add(new BannerEntry(blockX, bannerY, blockZ, facing, new ArrayList<>(patterns)));
    }

    /** 兼容旧构造（默认 overworld） */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns, int bannerY,
                     UUID ownerUUID, String ownerName) {
        this(location, Level.OVERWORLD, facing, patterns, location.x * 16 + 8, location.z * 16 + 8, bannerY,
                ownerUUID, ownerName);
    }

    /** 兼容旧构造（默认 overworld） */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns, int bannerY) {
        this(location, Level.OVERWORLD, facing, patterns,
                location.x * 16 + 8, location.z * 16 + 8, bannerY, null, null);
    }

    /** 兼容旧构造（默认 overworld） */
    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns) {
        this(location, Level.OVERWORLD, facing, patterns,
                location.x * 16 + 8, location.z * 16 + 8, 0, null, null);
    }

    public ChunkCoord getLocation() { return location; }
    public ResourceKey<Level> getDimension() { return dimension; }
    public String getFacing() { return facing; }
    public List<PatternEntry> getPatterns() { return patterns; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }

    /** 身份变更不重写旧圈地；保留 setter 供调试/兼容路径使用。 */
    public void setOwner(UUID ownerUUID, String ownerName) {
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
    }
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
        invalidateGeometry();
    }

    /**
     * 移除指定坐标的旗帜。
     *
     * @return 移除后该区块是否还有旗帜（true = 继续保留注册，false = 应注销）
     */
    public boolean removeBanner(int blockX, int blockY, int blockZ) {
        boolean removed = banners.removeIf(be -> be.blockX == blockX
                && be.blockY == blockY && be.blockZ == blockZ);
        if (removed) invalidateGeometry();
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

    /**
     * 丢弃缓存的扩展/影响坐标。保护范围配置热重载后，已有节点必须重算，
     * 否则仍会沿用旧距离。
     */
    public void invalidateGeometry() {
        rotatedExtension = null;
        claimSegments = null;
        influencedCoords = null;
    }

    /**
     * 计算影响坐标集：该节点所有旗帜、所有图案产生的射线格 + 边框格。
     * <p>
     * 边框只保护周长，不填充内部；内部由 {@code AreaService.rebuildGeometry()}
     * 把所有旗帜的射线汇总后交给 {@code Geometry.compute} 全局闭合填充。
     */
    public List<ChunkCoord> getInfluencedCoords() {
        if (influencedCoords != null) return influencedCoords;
        java.util.LinkedHashSet<ChunkCoord> cells = new java.util.LinkedHashSet<>();
        cells.add(location);
        for (int[] segment : getShapeSegments()) {
            ClaimShape.rasterize(segment, cells);
        }
        for (int[] segment : getBorderSegments()) {
            ClaimShape.rasterize(segment, cells);
        }
        influencedCoords = new ArrayList<>(cells);
        return influencedCoords;
    }

    /** 该节点所有旗帜的边框线段（绝对区块坐标），只用于白名单周长，不参与填充。 */
    public List<int[]> getBorderSegments() {
        List<int[]> out = new ArrayList<>();
        if (banners.isEmpty()) {
            appendBorderSegments(out, facing, patterns);
        } else {
            for (BannerEntry banner : banners) {
                appendBorderSegments(out, banner.facing(), banner.patterns());
            }
        }
        return out;
    }

    private void appendBorderSegments(List<int[]> out, String bannerFacing,
                                      List<PatternEntry> bannerPatterns) {
        ClaimRangeConfig config = ClaimRangeConfig.getInstance();
        for (PatternEntry pe : bannerPatterns) {
            int distance = config.distanceForColor(pe.color());
            for (int[] segment : ClaimShape.borderSegments(pe.patternId(), distance,
                    bannerFacing, config.directionsFor(pe.patternId()))) {
                out.add(new int[]{
                        location.x + segment[0], location.z + segment[1],
                        location.x + segment[2], location.z + segment[3]});
            }
        }
    }

    /**
     * 8 个方向的延伸射线线段：{@code {x1,z1,x2,z2}}，起点均为节点所在区块。
     * <p>
     * 顺序固定为 北→东北→东→东南→南→西南→西→西北；距离为 0 的方向不产生线段。
     */
    public List<int[]> getExtensionSegments() {
        Extension e = getExtension();
        List<int[]> out = new ArrayList<>(8);
        addSeg(out, 0, -e.getUp());                        // 北
        addSeg(out, e.getUpRight(), -e.getUpRight());      // 东北
        addSeg(out, e.getRight(), 0);                      // 东
        addSeg(out, e.getDownRight(), e.getDownRight());   // 东南
        addSeg(out, 0, e.getDown());                       // 南
        addSeg(out, -e.getDownLeft(), e.getDownLeft());    // 西南
        addSeg(out, -e.getLeft(), 0);                      // 西
        addSeg(out, -e.getUpLeft(), -e.getUpLeft());       // 西北
        return out;
    }

    /**
     * 该节点贡献给全局「化线为面」的线段集 = 所有旗帜、所有图案的**射线**。
     * <p>
     * <b>不再生成末端环</b>：单面旗帜的射线都从同一中心出发，构成星形图（无环），
     * 因此单旗永远不会自己形成面。只有多面旗帜的射线在全局图中互相接续/交叉时，
     * {@code Geometry.compute} 才会找到闭合面并填充内部。
     */
    public List<int[]> getShapeSegments() {
        if (claimSegments != null) return claimSegments;
        List<int[]> out = new ArrayList<>();
        if (banners.isEmpty()) {
            appendBannerSegments(out, facing, patterns);
        } else {
            for (BannerEntry banner : banners) {
                appendBannerSegments(out, banner.facing(), banner.patterns());
            }
        }
        claimSegments = out;
        return claimSegments;
    }

    /**
     * 单面旗帜的射线：按图案方向 + 图案颜色距离生成，再按该旗帜朝向旋转。
     * 多个图案层直接并集，不触发任何填充。
     */
    private void appendBannerSegments(List<int[]> out, String bannerFacing,
                                      List<PatternEntry> bannerPatterns) {
        ClaimRangeConfig config = ClaimRangeConfig.getInstance();
        for (PatternEntry pe : bannerPatterns) {
            int distance = config.distanceForColor(pe.color());
            List<String> directions = config.directionsFor(pe.patternId());
            for (int[] segment : ClaimShape.raySegments(pe.patternId(), distance,
                    bannerFacing, directions)) {
                out.add(new int[]{
                        location.x + segment[0], location.z + segment[1],
                        location.x + segment[2], location.z + segment[3]});
            }
        }
    }

    private void addSeg(List<int[]> out, int dx, int dz) {
        if (dx == 0 && dz == 0) return;
        out.add(new int[]{location.x, location.z, location.x + dx, location.z + dz});
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
