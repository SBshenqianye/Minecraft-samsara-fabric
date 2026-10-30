package com.shenqianye.samsara.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.math.Transformation;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 固定边界开关（旗帜高度）：玩家 UUID → 是否显示 */
    private static final Set<UUID> showFixed = new HashSet<>();
    /** 跟随边界开关（玩家高度）：玩家 UUID → 是否显示 */
    private static final Set<UUID> showDynamic = new HashSet<>();

    /** 右键旗帜时间：玩家 UUID → 最近右键时间（毫秒） */
    private static final Map<UUID, Long> rightClickTime = new HashMap<>();

    /** 右键旗帜后持续显示时长（毫秒） */
    private static final long RIGHT_CLICK_DURATION_MS = 10_000L;

    /** 持久化配置文件路径（随存档世界数据） */
    private static Path togglesPath() {
        return com.shenqianye.samsara.storage.StoragePaths.get().world("boundary-toggles.json");
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        // 注意：boundaryToggle/showFixed/showDynamic 不在这里 clear，
        // 因为它们已持久化到 boundary-toggles.json，重启后需要恢复
        rightClickTime.clear();
    }

    /** 切换玩家的持续边界显示开关，返回切换后的状态 */
    public static boolean toggleBoundary(UUID playerUUID) {
        if (boundaryToggle.contains(playerUUID)) {
            boundaryToggle.remove(playerUUID);
        } else {
            boundaryToggle.add(playerUUID);
        }
        saveToggles();
        return boundaryToggle.contains(playerUUID);
    }

    /** 切换固定边界显示（旗帜高度），返回切换后的状态 */
    public static boolean toggleFixed(UUID playerUUID) {
        if (showFixed.contains(playerUUID)) {
            showFixed.remove(playerUUID);
        } else {
            showFixed.add(playerUUID);
        }
        saveToggles();
        return showFixed.contains(playerUUID);
    }

    /** 切换跟随边界显示（玩家高度），返回切换后的状态 */
    public static boolean toggleDynamic(UUID playerUUID) {
        if (showDynamic.contains(playerUUID)) {
            showDynamic.remove(playerUUID);
        } else {
            showDynamic.add(playerUUID);
        }
        saveToggles();
        return showDynamic.contains(playerUUID);
    }

    /** 获取固定边界是否开启 */
    public static boolean isFixedOn(UUID playerUUID) {
        return showFixed.contains(playerUUID);
    }

    /** 获取跟随边界是否开启 */
    public static boolean isDynamicOn(UUID playerUUID) {
        return showDynamic.contains(playerUUID);
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

    // ---- 持久化 toggle 状态 ----

    private static void saveToggles() {
        try {
            TogglesData data = new TogglesData();
            data.boundary = new ArrayList<>(boundaryToggle.stream().map(UUID::toString).toList());
            data.fixed = new ArrayList<>(showFixed.stream().map(UUID::toString).toList());
            data.dynamic = new ArrayList<>(showDynamic.stream().map(UUID::toString).toList());
            Files.createDirectories(togglesPath().getParent());
            Files.writeString(togglesPath(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[samsara] boundary-toggles.json 写入失败: {}", e.getMessage());
        }
    }

    private static void loadToggles() {
        if (!Files.exists(togglesPath())) return;
        try {
            TogglesData data = GSON.fromJson(
                    Files.readString(togglesPath(), StandardCharsets.UTF_8), TogglesData.class);
            if (data == null) return;
            boundaryToggle.clear();
            showFixed.clear();
            showDynamic.clear();
            if (data.boundary != null) {
                for (String s : data.boundary) {
                    try { boundaryToggle.add(UUID.fromString(s)); } catch (Exception ignored) {}
                }
            }
            if (data.fixed != null) {
                for (String s : data.fixed) {
                    try { showFixed.add(UUID.fromString(s)); } catch (Exception ignored) {}
                }
            }
            if (data.dynamic != null) {
                for (String s : data.dynamic) {
                    try { showDynamic.add(UUID.fromString(s)); } catch (Exception ignored) {}
                }
            }
            Samsara.LOGGER.info("[samsara] 加载 boundary toggle: boundary={}, fixed={}, dynamic={}",
                    boundaryToggle.size(), showFixed.size(), showDynamic.size());
        } catch (Exception e) {
            Samsara.LOGGER.warn("[samsara] boundary-toggles.json 解析失败: {}", e.getMessage());
        }
    }

    /** 持久化数据模型 */
    static class TogglesData {
        List<String> boundary = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        List<String> dynamic = new ArrayList<>();
    }

    public static void init() {
        // 世界数据根在 SERVER_STARTING 才可用；toggle 状态在 SERVER_STARTED 读取
        ServerLifecycleEvents.SERVER_STARTED.register(server -> loadToggles());
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
                Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist(level.dimension());

                if (!globalDisplay) {
                    ChunkCoord playerChunk = new ChunkCoord(
                            player.blockPosition().getX() >> 4,
                            player.blockPosition().getZ() >> 4);
                    if (!whitelist.contains(playerChunk)) {
                        if (sharedDisplay) removeDisplays(player);
                        continue;
                    }
                }

                // 白名单为空时清除所有显示并跳过
                if (whitelist.isEmpty()) {
                    removeDisplays(player);
                    continue;
                }

                activePlayers.add(player.getUUID());

                // 计算管理区域边界
                Set<String> edges = calcBoundaryEdges(whitelist);

                // 1) 边界粒子（昼夜不同）
                long dayTime = level.getOverworldClockTime() % 24000;
                ParticleType<?> boundaryParticle = ClaimParticleConfig.getInstance()
                        .getParticleForTime(dayTime);
                ParticleOptions boundaryOpts = (ParticleOptions) boundaryParticle;

                UUID pid = player.getUUID();
                boolean dynOn = showDynamic.contains(pid);
                boolean fixOn = showFixed.contains(pid);
                boolean rightClicked = rightClickTime.containsKey(pid)
                        && System.currentTimeMillis() - rightClickTime.get(pid) < RIGHT_CLICK_DURATION_MS;

                // canShow = 常显开关 OR 手持旗帜 OR 右键后10秒内
                boolean canShow = boundaryToggle.contains(pid)
                        || player.getMainHandItem().is(ItemTags.BANNERS)
                        || rightClicked;

                // 跟随边界：canShow AND dynamic开关开
                if (canShow && dynOn) {
                    double feetY = player.getY();
                    sendBoundaryParticlesFromEdges(level, player, edges,
                            feetY + 2.0, boundaryOpts);
                    sendBoundaryParticlesFromEdges(level, player, edges,
                            feetY + 4.0, boundaryOpts);
                    // 化线为面：在保护区域内填充网格粒子
                    sendFillParticles(level, player, whitelist,
                            feetY + 2.0, boundaryOpts);
                }
                // 固定边界：canShow AND fixed开关开 AND bannerY有效
                double bannerY = AreaService.getInstance().getAvgBannerY(level.dimension());
                if (canShow && fixOn && bannerY > 1) {
                    sendBoundaryParticlesFromEdges(level, player, edges,
                            bannerY + 1.0, boundaryOpts);
                    sendBoundaryParticlesFromEdges(level, player, edges,
                            bannerY + 2.0, boundaryOpts);
                    // 化线为面：固定高度填充
                    sendFillParticles(level, player, whitelist,
                            bannerY + 1.0, boundaryOpts);
                }

                // 区块信息核心：管理区域几何中心
                double coreX = calcAreaCenterX(whitelist);
                double coreZ = calcAreaCenterZ(whitelist);

                // 文字位置：跟随边界开→跟随玩家；仅固定边界开→固定在旗帜高度
                boolean textFollowPlayer = canShow && dynOn;

                if (sharedDisplay) {
                    updateTextDisplayArrow(level, player, coreX, coreZ);
                } else {
                    // 光束目标Y：跟随模式→玩家高度；固定模式→旗帜高度
                    double beamTargetY = textFollowPlayer
                            ? player.getY() + 4.0
                            : (bannerY > 1 ? bannerY + 4.0 : player.getY() + 4.0);
                    sendParticleBeam(level, player, coreX, coreZ, beamTargetY);
                }
            }

            if (sharedDisplay) {
                Iterator<UUID> it = arrowDisplays.keySet().iterator();
                while (it.hasNext()) {
                    UUID id = it.next();
                    if (!activePlayers.contains(id)) {
                        Display.TextDisplay d = arrowDisplays.remove(id);
                        if (d != null && d.isAlive()) d.discard();
                    }
                }
                Iterator<UUID> it2 = infoDisplays.keySet().iterator();
                while (it2.hasNext()) {
                    UUID id = it2.next();
                    if (!activePlayers.contains(id)) {
                        Display.TextDisplay d = infoDisplays.remove(id);
                        if (d != null && d.isAlive()) d.discard();
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
            double x1 = Long.parseLong(p[0]), z1 = Long.parseLong(p[1]);
            double x2 = Long.parseLong(p[2]), z2 = Long.parseLong(p[3]);
            double len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
            if (len == 0) continue;
            double sx = Math.signum(x2 - x1);
            double sz = Math.signum(z2 - z1);
            // 2格一个粒子（减少粒子数量避免卡顿）
            for (double d = 0; d <= len; d += 2.0) {
                level.sendParticles(player, particleType,
                        true, true, x1 + sx * d, y, z1 + sz * d,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * 化线为面：检测边界是否闭合，闭合则在内部填充网格粒子。
     * <p>
     * 闭合判定：每条边界边恰好连接2条其他边，形成单一环路。
     * 填充方式：包围盒内每个区块中心，用射线法判断是否在闭合区域内。
     */
    private static void sendFillParticles(ServerLevel level, ServerPlayer player,
                                           Set<ChunkCoord> whitelist, double y,
                                           ParticleOptions particleType) {
        // 1. 计算边界边
        Set<String> edges = calcBoundaryEdges(whitelist);

        // 2. 检测是否闭合：每条边的端点恰好连接2条边
        if (!isClosedLoop(edges)) return;

        // 3. 包围盒
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord c : whitelist) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }

        // 4. 射线法：包围盒内每4个区块采样，判断区块中心是否在闭合区域内
        for (int cx = minX; cx <= maxX; cx += 4) {
            for (int cz = minZ; cz <= maxZ; cz += 4) {
                double px = cx * 16.0 + 8.0;
                double pz = cz * 16.0 + 8.0;
                if (isPointInEdges(px, pz, edges)) {
                    level.sendParticles(player, particleType,
                            true, true, px, y, pz,
                            1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    /**
     * 检测边界边集是否形成闭合环路。
     * 每条边用端点坐标表示，统计每个端点连接的边数，全部为2则闭合。
     */
    private static boolean isClosedLoop(Set<String> edges) {
        if (edges.isEmpty()) return false;
        // 端点 → 连接边数
        Map<String, Integer> vertexCount = new HashMap<>();
        for (String ek : edges) {
            String[] p = ek.split(",");
            // 边的两个端点
            String v1 = p[0] + "," + p[1];
            String v2 = p[2] + "," + p[3];
            vertexCount.merge(v1, 1, Integer::sum);
            vertexCount.merge(v2, 1, Integer::sum);
        }
        // 闭合环路：每个端点恰好连接2条边
        for (int count : vertexCount.values()) {
            if (count != 2) return false;
        }
        return true;
    }

    /**
     * 射线法判断点 (px, pz) 是否在边界边围成的闭合区域内。
     * 从点向右发射水平射线，计算与边的交点数，奇数=内部。
     */
    private static boolean isPointInEdges(double px, double pz, Set<String> edges) {
        int intersections = 0;
        for (String ek : edges) {
            String[] p = ek.split(",");
            double x1 = Long.parseLong(p[0]), z1 = Long.parseLong(p[1]);
            double x2 = Long.parseLong(p[2]), z2 = Long.parseLong(p[3]);
            // 仅处理水平线段（z1 == z2）用于射线交叉
            if (z1 == z2) continue;
            // 射线是 z = pz 的水平线，检查线段是否跨越 pz
            if ((z1 <= pz && z2 > pz) || (z2 <= pz && z1 > pz)) {
                // 计算交点的 x 坐标
                double xIntersect = x1 + (pz - z1) / (z2 - z1) * (x2 - x1);
                if (xIntersect > px) intersections++;
            }
        }
        return (intersections & 1) == 1; // 奇数 = 内部
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
     * 粒子光束：从玩家右手到目标点（targetY 随模式变化）。
     */
    private static void sendParticleBeam(ServerLevel level, ServerPlayer player,
                                          double targetX, double targetZ, double targetY) {
        ParticleOptions beamType = (ParticleOptions) ClaimParticleConfig.getInstance().getBeamParticle();
        var look = player.getViewVector(1.0f);
        double lookX = look.x;
        double lookZ = look.z;
        double lookLen = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLen < 0.001) return;
        lookX /= lookLen;
        lookZ /= lookLen;

        double rightX = lookZ;
        double rightZ = -lookX;

        // 手部位置
        double hx = player.getX() + rightX * 0.4 + lookX * 0.15;
        double hy = player.getY() + 0.6;
        double hz = player.getZ() + rightZ * 0.4 + lookZ * 0.15;

        double dx = targetX - hx;
        double dy = targetY - hy;
        double dz = targetZ - hz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0) return;

        int particles = (int) (dist / 1.0) + 1;
        for (int i = 2; i <= particles + 2; i++) {
            double t = (double) i / (particles + 4);
            level.sendParticles(player, beamType,
                    true, true, hx + dx * t, hy + dy * t, hz + dz * t,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 粒子文字：在管理区域中心上方用粒子写出区块尺寸。
     * textFollowPlayer=true → 跟随玩家高度；false → 固定在旗帜高度
     */
    private static void sendParticleText(ServerLevel level, ServerPlayer player,
                                          double centerX, double centerZ,
                                          Set<ChunkCoord> whitelist,
                                          boolean textFollowPlayer, double bannerY) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord c : whitelist) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        String text = (maxX - minX + 1) + "X" + (maxZ - minZ + 1);

        ParticleOptions fontType = (ParticleOptions) ClaimParticleConfig.getInstance().getFontParticle();
        double textY;
        if (textFollowPlayer) {
            // 跟随玩家：脚底 +4 + 身高 +1
            textY = player.getY() + 4.0 + player.getBbHeight() + 1.0;
        } else {
            // 固定在旗帜高度
            textY = (bannerY > 1 ? bannerY : player.getY()) + 5.0;
        }
        // 根据距离动态调整文字缩放：近距离 1.5（大字），远距离缩小使粒子更紧凑易读
        double textDist = Math.sqrt(
                (centerX - player.getX()) * (centerX - player.getX())
                        + (centerZ - player.getZ()) * (centerZ - player.getZ()));
        double dynamicScale = Math.max(0.8, 1.5 - Math.min(textDist / 120.0, 0.7));
        ClaimParticleText.drawFacingScaled(level, player, centerX, textY, centerZ, text, 6, fontType, dynamicScale);
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
