package com.shenqianye.samsara.claim;

import com.mojang.math.Transformation;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * 区块信息显示 v9（解耦版）。
 * <p>
 * 触发条件（任一即可）：
 * 1. 手持旗帜 → 读取 AreaService 管理列表 → 若玩家位于管理区域内 → 显示区块信息
 * 2. /samsara claim boundary 开启持续显示
 * 3. 右键旗帜后 10 秒内持续显示
 * <p>
 * 不关心具体是哪个旗帜管理该区域，只关心是否在管理区域内。
 * <p>
 * 共享显示 OFF（默认）：粒子方案，仅相关玩家可见
 * 共享显示 ON：TextDisplay 方案，所有人可见
 */
public class ClaimParticles {
    private static volatile boolean shuttingDown = false;

    private static final Map<UUID, Display.TextDisplay> arrowDisplays = new HashMap<>();
    private static final Map<UUID, Display.TextDisplay> infoDisplays = new HashMap<>();

    /** 持久显示边界开关：玩家 UUID → 是否开启 */
    private static final Set<UUID> boundaryToggle = new HashSet<>();

    /** 右键旗帜时间：玩家 UUID → 最近右键时间（毫秒） */
    private static final Map<UUID, Long> rightClickTime = new HashMap<>();

    /** 右键旗帜后持续显示时长（毫秒） */
    private static final long RIGHT_CLICK_DURATION_MS = 10_000L;

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static final double SCALE_REFERENCE = 10.0;
    private static final float SCALE_MIN = 0.5f;
    private static final float SCALE_MAX = 10.0f;

    private static EntityType<?> cachedTextDisplayType;

    public static void shutdown() {
        shuttingDown = true;
        for (Display.TextDisplay d : arrowDisplays.values()) {
            if (d != null && d.isAlive()) d.discard();
        }
        arrowDisplays.clear();
        for (Display.TextDisplay d : infoDisplays.values()) {
            if (d != null && d.isAlive()) d.discard();
        }
        infoDisplays.clear();
        boundaryToggle.clear();
        rightClickTime.clear();
    }

    /** 切换玩家的持续边界显示开关，返回切换后的状态 */
    public static boolean toggleBoundary(UUID playerUUID) {
        if (boundaryToggle.contains(playerUUID)) {
            boundaryToggle.remove(playerUUID);
            return false;
        } else {
            boundaryToggle.add(playerUUID);
            return true;
        }
    }

    /** 玩家右键旗帜时调用，记录时间戳以持续显示 10 秒 */
    public static void notifyRightClick(UUID playerUUID) {
        rightClickTime.put(playerUUID, System.currentTimeMillis());
    }

    /** 判断玩家是否应该看到边界 */
    private static boolean shouldShowBoundary(ServerPlayer player) {
        // 条件 1：手持旗帜
        if (player.getMainHandItem().is(ItemTags.BANNERS)) return true;
        // 条件 2：持久显示已开启
        if (boundaryToggle.contains(player.getUUID())) return true;
        // 条件 3：右键旗帜后 10 秒内
        Long lastClick = rightClickTime.get(player.getUUID());
        if (lastClick != null && System.currentTimeMillis() - lastClick < RIGHT_CLICK_DURATION_MS) return true;
        return false;
    }

    private static EntityType<?> getTextDisplayType() {
        if (cachedTextDisplayType == null) {
            cachedTextDisplayType = BuiltInRegistries.ENTITY_TYPE.get(
                    Identifier.fromNamespaceAndPath("minecraft", "text_display")).orElseThrow().value();
        }
        return cachedTextDisplayType;
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (shuttingDown) return;

            boolean sharedDisplay = ClaimParticleConfig.getInstance().isSharedDisplay();
            Set<UUID> activePlayers = new HashSet<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.level() instanceof ServerLevel level)) continue;

                // 手持旗帜 OR 持续显示开关 OR 右键后10秒内
                boolean showBoundary = shouldShowBoundary(player);
                if (!showBoundary) {
                    if (sharedDisplay) removeDisplays(player);
                    continue;
                }

                // 全图显示模式：跳过管理区域检查；否则要求玩家在管理区域内
                boolean globalDisplay = ClaimParticleConfig.getInstance().isGlobalDisplay();
                Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist();

                if (!globalDisplay) {
                    ChunkCoord playerChunk = new ChunkCoord(
                            player.blockPosition().getX() >> 4,
                            player.blockPosition().getZ() >> 4);
                    if (!whitelist.contains(playerChunk)) {
                        if (sharedDisplay) removeDisplays(player);
                        continue;
                    }
                }

                activePlayers.add(player.getUUID());

                // 计算管理区域边界
                Set<String> edges = calcBoundaryEdges(whitelist);

                // 1) 边界粒子（昼夜不同）
                long dayTime = level.getOverworldClockTime() % 24000;
                ParticleType<?> boundaryParticle = ClaimParticleConfig.getInstance()
                        .getParticleForTime(dayTime);
                sendBoundaryParticlesFromEdges(level, player, edges,
                        player.getEyeY(),
                        (ParticleOptions) boundaryParticle);

                // 区块信息核心：管理区域几何中心（化线为面后的区域中心）
                double coreX = calcAreaCenterX(whitelist);
                double coreZ = calcAreaCenterZ(whitelist);

                if (sharedDisplay) {
                    updateTextDisplayArrow(level, player, coreX, coreZ);
                    updateTextDisplayInfo(level, player, coreX, coreZ, whitelist);
                } else {
                    sendParticleBeam(level, player, coreX, coreZ);
                    sendParticleText(level, player, coreX, coreZ, whitelist);
                }
            }

            if (sharedDisplay) {
                Iterator<UUID> it = arrowDisplays.keySet().iterator();
                while (it.hasNext()) {
                    UUID id = it.next();
                    if (!activePlayers.contains(id)) {
                        Display.TextDisplay d = arrowDisplays.remove(id);
                        if (d != null && d.isAlive()) d.discard();
                        Display.TextDisplay d2 = infoDisplays.remove(id);
                        if (d2 != null && d2.isAlive()) d2.discard();
                    }
                }
            }
        });
    }

    // ========================================================================
    //  区域边界计算（从 whitelist 集合计算边界边）
    // ========================================================================

    private static Set<String> calcBoundaryEdges(Set<ChunkCoord> area) {
        Set<String> edges = new HashSet<>();
        int[] dx = {0, 0, 1, -1};
        int[] dz = {1, -1, 0, 0};
        for (ChunkCoord c : area) {
            for (int i = 0; i < 4; i++) {
                ChunkCoord nb = new ChunkCoord(c.x + dx[i], c.z + dz[i]);
                if (!area.contains(nb)) {
                    long x1, z1, x2, z2;
                    switch (i) {
                        case 0 -> { x1 = c.x * 16L; z1 = (c.z + 1) * 16L; x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L; }
                        case 1 -> { x1 = c.x * 16L; z1 = c.z * 16L; x2 = (c.x + 1) * 16L; z2 = c.z * 16L; }
                        case 2 -> { x1 = (c.x + 1) * 16L; z1 = c.z * 16L; x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L; }
                        default -> { x1 = c.x * 16L; z1 = c.z * 16L; x2 = c.x * 16L; z2 = (c.z + 1) * 16L; }
                    }
                    String key = Math.min(x1, x2) + "," + Math.min(z1, z2) + ","
                            + Math.max(x1, x2) + "," + Math.max(z1, z2);
                    edges.add(key);
                }
            }
        }
        return edges;
    }

    private static void sendBoundaryParticlesFromEdges(ServerLevel level, ServerPlayer player,
                                                        Set<String> edges, double y,
                                                        ParticleOptions particleType) {
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
                        true, true, x1 + sx * d, y, z1 + sz * d,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static double calcAreaCenterX(Set<ChunkCoord> area) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (ChunkCoord c : area) { min = Math.min(min, c.x); max = Math.max(max, c.x); }
        return (min + max + 1) * 8.0; // 区块中心
    }

    private static double calcAreaCenterZ(Set<ChunkCoord> area) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (ChunkCoord c : area) { min = Math.min(min, c.z); max = Math.max(max, c.z); }
        return (min + max + 1) * 8.0;
    }

    // ========================================================================
    //  独占模式（粒子方案，仅手持旗帜的玩家可见）
    // ========================================================================

    /**
     * 粒子光束：从玩家右手到管理区域核心。
     * 使用 getViewVector 获取实际视线方向（而非身体朝向）。
     * 光束粒子类型可通过配置独立设置。
     */
    private static void sendParticleBeam(ServerLevel level, ServerPlayer player,
                                          double targetX, double targetZ) {
        ParticleOptions beamType = (ParticleOptions) ClaimParticleConfig.getInstance().getBeamParticle();
        // 使用视线方向向量（包含头部旋转，不仅仅是身体朝向）
        var look = player.getViewVector(1.0f);
        double lookX = look.x;
        double lookZ = look.z;
        double lookLen = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLen < 0.001) return;
        lookX /= lookLen;
        lookZ /= lookLen;

        // 右手方向 = 视线方向顺时针旋转90度
        double rightX = lookZ;   // 顺时针90度
        double rightZ = -lookX;

        // 手部位置：右手侧 0.4 格 + 前方 0.15 格，Y = 腰部高度
        double hx = player.getX() + rightX * 0.4 + lookX * 0.15;
        double hy = player.getY() + 0.6;
        double hz = player.getZ() + rightZ * 0.4 + lookZ * 0.15;

        double dx = targetX - hx;
        double dz = targetZ - hz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return;

        int particles = (int) (dist / 1.5) + 1;
        for (int i = 2; i <= particles + 2; i++) {
            double t = (double) i / (particles + 4);
            level.sendParticles(player, beamType,
                    true, true, hx + dx * t, hy, hz + dz * t,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 粒子文字：在管理区域中心上方用粒子写出区块尺寸。
     * 字体粒子类型可通过配置独立设置。
     */
    private static void sendParticleText(ServerLevel level, ServerPlayer player,
                                          double centerX, double centerZ,
                                          Set<ChunkCoord> whitelist) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord c : whitelist) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        String text = (maxX - minX + 1) + "X" + (maxZ - minZ + 1);

        ParticleOptions fontType = (ParticleOptions) ClaimParticleConfig.getInstance().getFontParticle();
        double eyeY = player.getY() + player.getBbHeight() + 1.0;
        ClaimParticleText.drawFacing(level, player, centerX, eyeY, centerZ, text, 6, fontType);
    }

    // ========================================================================
    //  共享模式（TextDisplay 方案，所有人可见）
    // ========================================================================

    private static float computeScale(double playerX, double playerZ, double targetX, double targetZ) {
        double dx = playerX - targetX;
        double dz = playerZ - targetZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double scale = dist / SCALE_REFERENCE;
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, (float) scale));
    }

    private static void applyScale(Display entity, float scale) {
        entity.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private static void updateTextDisplayArrow(ServerLevel level, ServerPlayer player,
                                               double targetX, double targetZ) {
        UUID playerId = player.getUUID();
        Display.TextDisplay display = arrowDisplays.get(playerId);

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double yaw = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double cross = dx * fz - dz * fx;
        double dot = dx * fx + dz * fz;
        double angle = Math.atan2(cross, dot);
        double normalized = ((-angle) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        int index = (int) Math.round(normalized / (Math.PI / 4)) % 8;

        Component text = Component.literal(ARROWS[index]).withStyle(
                Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true));
        double eyeY = player.getY() + player.getBbHeight();
        float scale = computeScale(player.getX(), player.getZ(), targetX, targetZ);

        if (display == null || !display.isAlive()) {
            display = new Display.TextDisplay(getTextDisplayType(), level);
            display.setText(text);
            display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            display.setPos(targetX, eyeY, targetZ);
            display.setNoGravity(true); display.setSilent(true); display.setViewRange(100f);
            level.addFreshEntity(display);
            arrowDisplays.put(playerId, display);
        } else {
            display.setText(text);
            display.teleportTo(targetX, eyeY, targetZ);
        }
        applyScale(display, scale);
    }

    private static void updateTextDisplayInfo(ServerLevel level, ServerPlayer player,
                                              double centerX, double centerZ,
                                              Set<ChunkCoord> whitelist) {
        UUID playerId = player.getUUID();
        Display.TextDisplay display = infoDisplays.get(playerId);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord c : whitelist) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }

        Component text = Component.literal((maxX - minX + 1) + "X" + (maxZ - minZ + 1)).withStyle(
                Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true));
        double eyeY = player.getY() + player.getBbHeight() + 1.0;
        float scale = computeScale(player.getX(), player.getZ(), centerX, centerZ);

        if (display == null || !display.isAlive()) {
            display = new Display.TextDisplay(getTextDisplayType(), level);
            display.setText(text);
            display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            display.setPos(centerX, eyeY, centerZ);
            display.setNoGravity(true); display.setSilent(true); display.setViewRange(100f);
            level.addFreshEntity(display);
            infoDisplays.put(playerId, display);
        } else {
            display.setText(text);
            display.teleportTo(centerX, eyeY, centerZ);
        }
        applyScale(display, scale);
    }

    private static void removeDisplays(ServerPlayer player) {
        Display.TextDisplay d = arrowDisplays.remove(player.getUUID());
        if (d != null && d.isAlive()) d.discard();
        Display.TextDisplay d2 = infoDisplays.remove(player.getUUID());
        if (d2 != null && d2.isAlive()) d2.discard();
    }
}
