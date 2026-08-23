package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 旗帜圈地交互（设计文档 §6.2.1 + §6.7）。
 * <p>
 * 空手/手持旗帜 右键已放置的圈地旗 → 聊天显示详情 + 粒子预览影响范围。
 * 自然生成旗帜（结构旗帜）无方向图案 → 自动跳过。
 */
public class ClaimInteractions {
    /** 防抖：每个玩家上次交互的 tick 时间 */
    private static final Map<UUID, Long> lastInteraction = new java.util.HashMap<>();
    private static final long DEBOUNCE_TICKS = 20; // 1 秒冷却

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            // 防抖：同一玩家 1 秒内不重复触发
            long now = level.getGameTime();
            Long last = lastInteraction.get(sp.getUUID());
            if (last != null && now - last < DEBOUNCE_TICKS) return InteractionResult.PASS;
            lastInteraction.put(sp.getUUID(), now);

            BlockPos pos = hitResult.getBlockPos();
            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                return InteractionResult.PASS;
            }
            List<ClaimNode.PatternEntry> patterns = readPatterns(be);
            if (patterns.isEmpty()) return InteractionResult.PASS;

            ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
            String facing = getFacing(level, pos);
            ClaimNode node = new ClaimNode(chunkPos, facing, patterns);
            Extension ext = node.getExtension();
            if (!ext.hasAnyDirection()) return InteractionResult.PASS;

            // 显示详情
            List<ChunkCoord> influenced = node.getInfluencedCoords();
            boolean protected_ = AreaService.getInstance().isProtected(chunkPos.x, chunkPos.z);
            StringBuilder sb = new StringBuilder();
            sb.append("[圈地旗] ").append(chunkPos);
            sb.append(" 朝向 ").append(facing);
            sb.append(protected_ ? " ✓ 受保护" : " ✗ 未保护");
            sb.append("\n  影响区块: ").append(influenced.size()).append(" 个");
            sb.append(" · 方向: ");
            if (ext.getUp() > 0) sb.append("北").append(ext.getUp()).append(" ");
            if (ext.getDown() > 0) sb.append("南").append(ext.getDown()).append(" ");
            if (ext.getLeft() > 0) sb.append("西").append(ext.getLeft()).append(" ");
            if (ext.getRight() > 0) sb.append("东").append(ext.getRight()).append(" ");
            if (ext.getLeftUp() > 0) sb.append("西北").append(ext.getLeftUp()).append(" ");
            if (ext.getRightUp() > 0) sb.append("东北").append(ext.getRightUp()).append(" ");
            if (ext.getLeftDown() > 0) sb.append("西南").append(ext.getLeftDown()).append(" ");
            if (ext.getRightDown() > 0) sb.append("东南").append(ext.getRightDown()).append(" ");
            sp.sendSystemMessage(Component.literal("[周目管理] " + sb.toString().trim()));

            // 立即发送粒子预览（§6.7：右键时即时反馈，Y 轴跟随玩家）
            if (level instanceof ServerLevel sl) {
                double particleY = sp.getY() + 0.5;
                net.minecraft.core.particles.ParticleType<?> boundaryParticle =
                        ClaimParticleConfig.getInstance().getParticleForTime(sl.getOverworldClockTime() % 24000);
                sendBoundaryParticles(sl, sp, chunkPos, influenced, particleY,
                        (net.minecraft.core.particles.ParticleOptions) boundaryParticle);
                // 箭头和文字由 ClaimParticles 的 TextDisplay 持续处理
            }
            return InteractionResult.SUCCESS;
        });
    }

    /** 发送边界粒子（§6.7：沿保护区域边界线均匀放置，粒子类型可配置） */
    static void sendBoundaryParticles(ServerLevel level, ServerPlayer player,
                                       ChunkCoord center, List<ChunkCoord> influenced, double y,
                                       net.minecraft.core.particles.ParticleOptions particleType) {
        Set<ChunkCoord> area = new HashSet<>(influenced);
        area.add(center);
        // 收集所有朝外的边（每条边 = 两个端点）
        Set<String> edges = new HashSet<>();
        int[] dx = {0, 0, 1, -1};
        int[] dz = {1, -1, 0, 0};
        for (ChunkCoord c : area) {
            for (int i = 0; i < 4; i++) {
                ChunkCoord nb = new ChunkCoord(c.x + dx[i], c.z + dz[i]);
                if (!area.contains(nb)) {
                    long x1, z1, x2, z2;
                    switch (i) {
                        case 0 -> { // south edge: z=(c.z+1)*16
                            x1 = c.x * 16L; z1 = (c.z + 1) * 16L;
                            x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L;
                        }
                        case 1 -> { // north edge: z=c.z*16
                            x1 = c.x * 16L; z1 = c.z * 16L;
                            x2 = (c.x + 1) * 16L; z2 = c.z * 16L;
                        }
                        case 2 -> { // east edge: x=(c.x+1)*16
                            x1 = (c.x + 1) * 16L; z1 = c.z * 16L;
                            x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L;
                        }
                        default -> { // west edge: x=c.x*16
                            x1 = c.x * 16L; z1 = c.z * 16L;
                            x2 = c.x * 16L; z2 = (c.z + 1) * 16L;
                        }
                    }
                    String key = Math.min(x1, x2) + "," + Math.min(z1, z2) + ","
                            + Math.max(x1, x2) + "," + Math.max(z1, z2);
                    edges.add(key);
                }
            }
        }
        // 沿每条边均匀放置 CRIT 粒子（每 4 格一个，生命周期短无重影）
        for (String ek : edges) {
            String[] p = ek.split(",");
            long x1 = Long.parseLong(p[0]), z1 = Long.parseLong(p[1]);
            long x2 = Long.parseLong(p[2]), z2 = Long.parseLong(p[3]);
            long len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
            if (len == 0) continue;
            long sx = Long.signum(x2 - x1);
            long sz = Long.signum(z2 - z1);
            for (long d = 0; d <= len; d += 4) {
                level.sendParticles(player, particleType,
                        true, false, x1 + sx * d, y, z1 + sz * d,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * 用粒子在保护区域中心"写"出区块尺寸 + 方向信息（替代旧版 ArmorStand 文字）。
     * 同时沿各方向生成<b>彩色方向光束</b>：光束长度 = 该方向延伸的区块数（封顶 16），
     * 颜色区分方向（N=白 S=火 E=堆肥 W=村民绿，对角=魂火/女巫/传送门/_obsidian 泪）。
     */
    static void spawnClaimInfoDisplay(ServerLevel level, ServerPlayer player, ChunkCoord center,
                                       List<ChunkCoord> influenced, double y) {
        // 计算边界与中心
        int minX = center.x, maxX = center.x;
        int minZ = center.z, maxZ = center.z;
        for (ChunkCoord c : influenced) {
            minX = Math.min(minX, c.x);
            maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z);
            maxZ = Math.max(maxZ, c.z);
        }
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;

        // 1) 尺寸文字：在旗帜所在区块中心上方 7~8 格，始终朝向玩家（billboard）
        double bcx = center.x * 16 + 8;
        double bcz = center.z * 16 + 8;
        String sizeText = sizeX + "X" + sizeZ;
        ClaimParticleText.drawFacing(level, player,
                bcx, y + 7.0, bcz,
                sizeText, 6, ParticleTypes.ENCHANT);

        // 2) 方向光束：从旗帜位置向各方向延伸，长度=区块数（封顶 16），颜色区分方向，末端带箭头
        int extN = 0, extS = 0, extW = 0, extE = 0;
        int extNW = 0, extNE = 0, extSW = 0, extSE = 0;
        for (ChunkCoord c : influenced) {
            int ddx = c.x - center.x;
            int ddz = c.z - center.z;
            int dist = Math.max(Math.abs(ddx), Math.abs(ddz));
            if (ddx == 0 && ddz < 0) extN = Math.max(extN, dist);
            else if (ddx == 0 && ddz > 0) extS = Math.max(extS, dist);
            else if (ddx < 0 && ddz == 0) extW = Math.max(extW, dist);
            else if (ddx > 0 && ddz == 0) extE = Math.max(extE, dist);
            else if (ddx < 0 && ddz < 0) extNW = Math.max(extNW, dist);
            else if (ddx > 0 && ddz < 0) extNE = Math.max(extNE, dist);
            else if (ddx < 0 && ddz > 0) extSW = Math.max(extSW, dist);
            else if (ddx > 0 && ddz > 0) extSE = Math.max(extSE, dist);
        }
        spawnDirectionBeam(level, player, bcx, bcz, y, 0, -1, Math.min(extN, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, 0, 1, Math.min(extS, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, -1, 0, Math.min(extW, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, 1, 0, Math.min(extE, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, -1, -1, Math.min(extNW, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, 1, -1, Math.min(extNE, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, -1, 1, Math.min(extSW, 16), ParticleTypes.ENCHANT);
        spawnDirectionBeam(level, player, bcx, bcz, y, 1, 1, Math.min(extSE, 16), ParticleTypes.ENCHANT);
    }

    /**
     * 从中心 (bx,bz) 沿 (ddx,ddz) 方向生成长度为 len 格的光束（每格 1 粒子）。
     * 不再在末端绘制箭头——箭头由 {@link #spawnDirectionArrow} 统一绘制。
     */
    private static void spawnDirectionBeam(ServerLevel level, ServerPlayer player,
                                           double bx, double bz, double y,
                                           int ddx, int ddz, int len,
                                           net.minecraft.core.particles.ParticleOptions type) {
        if (len <= 0) return;
        for (int i = 1; i <= len; i++) {
            level.sendParticles(player, type,
                    true, false, bx + ddx * i, y, bz + ddz * i,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 在玩家头顶绘制旋转方向箭头（billboard，始终面朝玩家）。
     * <p>
     * 箭头指向旗帜方向，根据玩家相对旗帜的角度平滑旋转：
     * <ul>
     *   <li>旗帜在前方（玩家面朝旗帜）→ 箭头朝上 ↑</li>
     *   <li>旗帜在左方 → 箭头朝左 ←</li>
     *   <li>旗帜在后方 → 箭头朝下 ↓</li>
     *   <li>旗帜在右方 → 箭头朝右 →</li>
     * </ul>
     * 旋转角度连续，不限于四个方向。
     *
     * @param level    服务端世界
     * @param player   玩家
     * @param bannerX  旗帜方块中心 X 世界坐标
     * @param bannerZ  旗帜方块中心 Z 世界坐标
     * @param arrowY   箭头中心 Y 世界坐标
     */
    static void spawnDirectionArrow(ServerLevel level, ServerPlayer player,
                                    double bannerX, double bannerZ,
                                    double arrowY) {
        // 1. 计算玩家到旗帜的方向向量（XZ 平面）
        double dx = bannerX - player.getX();
        double dz = bannerZ - player.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0) return; // 太近不显示

        // 2. 计算玩家朝向（XZ 平面单位向量）
        double yaw = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);

        // 3. 计算相对角度
        //    cross > 0 → 旗帜在玩家左方，dot > 0 → 旗帜在玩家前方
        double cross = dx * fz - dz * fx;
        double dot = dx * fx + dz * fz;
        double angle = Math.atan2(cross, dot); // 逆时针为正（左方为正）

        // 4. Billboard 平面坐标轴
        //    right = 玩家右方（水平，垂直于朝向）
        //    up    = 世界 Y 轴（垂直向上）
        double rightX = -fz;
        double rightZ = fx;

        // 5. 箭头形状（局部坐标，旋转前：上方为正 Y）
        //    箭杆 + V 形箭头，共 8 个粒子
        double[][] arrow = {
            // 箭杆（从中心向上延伸）
            {0, 0}, {0, 0.8}, {0, 1.6},
            // 箭头左翼
            {-0.7, 2.2}, {-1.2, 2.8},
            // 箭头右翼
            {0.7, 2.2}, {1.2, 2.8},
            // 箭头顶点
            {0, 3.2}
        };

        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        for (double[] pt : arrow) {
            double lx = pt[0];
            double ly = pt[1];
            double projX = lx * cos - ly * sin;
            double projY = lx * sin + ly * cos;
            // 箭头中心在 bannerX/bannerZ（旗帜位置），不在玩家位置
            level.sendParticles(player, ParticleTypes.ENCHANT,
                    true, false,
                    bannerX + projX * rightX,
                    arrowY + projY,
                    bannerZ + projX * rightZ,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 从 BannerBlockEntity 读取图案列表（public 供 ClaimCommand.scan 调用） */
    public static List<ClaimNode.PatternEntry> readPatterns(BannerBlockEntity be) {
        List<ClaimNode.PatternEntry> result = new ArrayList<>();
        BannerPatternLayers layers = be.getPatterns();
        if (layers == null) return result;
        for (BannerPatternLayers.Layer layer : layers.layers()) {
            String id = layer.pattern().value().assetId().getPath();
            int color = layer.color().ordinal();
            result.add(new ClaimNode.PatternEntry(id, color));
        }
        return result;
    }

    /** 判断旗帜是否有方向图案（= 可能是圈地旗） */
    public static boolean hasDirectionPatterns(List<ClaimNode.PatternEntry> patterns) {
        ClaimNode node = new ClaimNode(new ChunkCoord(0, 0), "north", patterns);
        return node.getExtension().hasAnyDirection();
    }

    /** 获取旗帜朝向（facing = 旗面朝向方向）
     * <p>
     * 站立旗帜：ROTATION_16 直接映射到 8 方向（0=south, 8=north, 12=east...）
     * <p>
     * 墙挂旗帜：FACING 属性指向贴墙方向（与旗面朝向相反），需要反转：
     * FACING=EAST → 旗面朝 WEST，所以返回 "west"
     */
    static String getFacing(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.FACING)) {
            // 墙挂旗帜：FACING 指向贴墙方向，需反转得到旗面朝向
            return state.getValue(BlockStateProperties.FACING).getOpposite().toString().toLowerCase();
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            int rot = state.getValue(BlockStateProperties.ROTATION_16);
            return switch (rot % 16) {
                case 0 -> "south"; case 2 -> "south_west";
                case 4 -> "west"; case 6 -> "north_west";
                case 8 -> "north"; case 10 -> "north_east";
                case 12 -> "east"; case 14 -> "south_east";
                default -> "south";
            };
        }
        return "north";
    }
}
