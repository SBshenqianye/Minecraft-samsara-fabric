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
    /** 影响坐标集（延迟计算） */
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

    /** 计算影响坐标集（从节点位置沿 8 个方向延伸出的全部格点，用于展示/粒子预览） */
    public List<ChunkCoord> getInfluencedCoords() {
        if (influencedCoords != null) return influencedCoords;
        influencedCoords = new ArrayList<>();
        for (int[] s : getExtensionSegments()) {
            addLine(location, s[2] - s[0], s[3] - s[1], influencedCoords);
        }
        return influencedCoords;
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
     * 按角度顺序（北→东北→东→东南→南→西南→西→西北）排列的延伸末端格点。
     * <p>
     * 交给 {@link com.shenqianye.samsara.geometry.Geometry#ringSegments} 连成闭合环后，
     * 图形才有"内部"可填——这是"化线为面"能产出面的前提。
     */
    public List<ChunkCoord> getExtensionEndpoints() {
        Extension e = getExtension();
        List<ChunkCoord> out = new ArrayList<>(8);
        addEnd(out, 0, -e.getUp());
        addEnd(out, e.getUpRight(), -e.getUpRight());
        addEnd(out, e.getRight(), 0);
        addEnd(out, e.getDownRight(), e.getDownRight());
        addEnd(out, 0, e.getDown());
        addEnd(out, -e.getDownLeft(), e.getDownLeft());
        addEnd(out, -e.getLeft(), 0);
        addEnd(out, -e.getUpLeft(), -e.getUpLeft());
        return out;
    }

    /**
     * 该节点贡献给「化线为面」的完整线段集 = **8 条射线 + 末端环**。
     * <p>
     * 只给射线是不够的：射线都从同一节点出发，构成星形图（无环），
     * 面遍历找不到有界面 → 保护区域只会有"线"、没有"面"。
     * 补上末端环后图形闭合，才能被填充出内部。
     */
    public List<int[]> getShapeSegments() {
        List<int[]> segs = new ArrayList<>(getExtensionSegments());
        segs.addAll(com.shenqianye.samsara.geometry.Geometry.ringSegments(getExtensionEndpoints()));
        return segs;
    }

    private void addSeg(List<int[]> out, int dx, int dz) {
        if (dx == 0 && dz == 0) return;
        out.add(new int[]{location.x, location.z, location.x + dx, location.z + dz});
    }

    private void addEnd(List<ChunkCoord> out, int dx, int dz) {
        if (dx == 0 && dz == 0) return;
        out.add(new ChunkCoord(location.x + dx, location.z + dz));
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
