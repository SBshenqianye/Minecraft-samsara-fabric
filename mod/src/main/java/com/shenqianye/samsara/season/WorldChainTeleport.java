package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.CommandConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跨世界传送链（F-29~F-34），维度模型：
 * <ul>
 *   <li>主世界虚空 → 同层下界；</li>
 *   <li>下界虚空 → 下层栈层主世界；栈底交给原版死亡；</li>
 *   <li>下界顶部 → 同层主世界；</li>
 *   <li>主世界顶部 → 上层栈层下界。</li>
 * </ul>
 * 同层下界 = 该层自己的 nether；栈顶用 minecraft:the_nether，旧层用 samsara:stack_n/the_nether。
 */
public final class WorldChainTeleport {
    private static final int CHECK_INTERVAL_TICKS = 5;
    private static final long COOLDOWN_MS = 3000L;
    /** 虚空高空落点后的防乒乓窗口：覆盖慢降与区块加载导致的短暂 Y 抖动。 */
    private static final long VOID_DROP_GUARD_MS = 5000L;

    private static final Map<UUID, Long> lastTeleport = new HashMap<>();
    private static final Map<UUID, String> lastNotice = new HashMap<>();
    private static final Map<UUID, Long> voidDropGuardUntil = new HashMap<>();
    private static int counter;

    private WorldChainTeleport() {
    }

    public static synchronized void load(MinecraftServer server) {
        Samsara.LOGGER.info("[周目管理 samsara] {}", StackDimensionLoader.status());
    }

    public static synchronized void unload() {
        lastTeleport.clear();
        lastNotice.clear();
        voidDropGuardUntil.clear();
    }

    public static synchronized String statusText() {
        return StackDimensionLoader.status();
    }

    public static synchronized void tick(MinecraftServer server) {
        if (++counter < CHECK_INTERVAL_TICKS) {
            return;
        }
        counter = 0;
        long now = System.currentTimeMillis();
        voidDropGuardUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                check(server, player);
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 跨世界传送检查异常（{}）: {}",
                        player.getName().getString(), e.getMessage());
            }
        }
    }

    private static void check(MinecraftServer server, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        StackManifest manifest = StackManifest.load(StackPaths.worldDir(server));
        String layerId = StackPaths.layerIdOf(level.dimension());
        StackLayer layer = layerId == null ? null : manifest.byId(layerId);
        String dim = StackPaths.dimensionOf(level.dimension());
        if (layer == null || dim == null) {
            return;
        }
        int minY = level.dimensionType().minY();
        double y = player.getY();

        if (WorldChainRules.isVoid(y, minY)) {
            if ("overworld".equals(dim)) {
                ServerLevel nether = StackTeleport.level(server, layer, "the_nether");
                if (nether != null) {
                    int tx = WorldChainRules.toNether(player.getX());
                    int tz = WorldChainRules.toNether(player.getZ());
                    teleport(player, nether, layer, tx, tz, safeNetherY(nether, tx, tz),
                            "虚空坠落 → 同层下界");
                } else {
                    notice(player, "void-nether:" + layer.id, "同层下界（" + layer.id
                            + "）加载失败：" + StackDimensionLoader.failureReason(layer.id));
                }
            } else if ("the_nether".equals(dim)) {
                StackLayer lower = manifest.below(layer);
                if (lower == null) {
                    return; // F-34：栈底下界虚空 → 原版死亡
                }
                ServerLevel target = StackTeleport.level(server, lower, "overworld");
                if (target == null) {
                    notice(player, "nether-void:" + lower.id, "下层世界 " + lower.id
                            + " 加载失败：" + StackDimensionLoader.failureReason(lower.id));
                    return;
                }
                int tx = WorldChainRules.toOverworld(player.getX());
                int tz = WorldChainRules.toOverworld(player.getZ());
                int dropY = resolveVoidDropY(target, tx, tz, lower);
                teleport(player, target, lower, tx, tz, dropY,
                        "下界虚空 → 下层 " + lower.id + " 主世界高空", true);
            }
            return;
        }

        if (WorldChainRules.isTop(y, minY, level.dimensionType().height(),
                "the_nether".equals(dim) ? WorldChainRules.DimensionKind.NETHER
                        : WorldChainRules.DimensionKind.OVERWORLD)) {
            if ("the_nether".equals(dim)) {
                ServerLevel overworld = StackTeleport.level(server, layer, "overworld");
                if (overworld != null) {
                    int tx = WorldChainRules.toOverworld(player.getX());
                    int tz = WorldChainRules.toOverworld(player.getZ());
                    teleport(player, overworld, layer, tx, tz, safeY(overworld, tx, tz),
                            "下界顶部 → 同层主世界");
                } else {
                    notice(player, "top-overworld:" + layer.id, "同层主世界（" + layer.id
                            + "）加载失败：" + StackDimensionLoader.failureReason(layer.id));
                }
            } else if ("overworld".equals(dim)) {
                long guardUntil = voidDropGuardUntil.getOrDefault(player.getUUID(), 0L);
                if (WorldChainRules.isVoidDropGuardActive(System.currentTimeMillis(), guardUntil)) {
                    // 刚经历下界虚空 → 高空落点：守卫期内不把落点附近的 Y 抖动判成上行。
                    return;
                }
                StackLayer upper = manifest.above(layer);
                if (upper == null) {
                    return;
                }
                ServerLevel target = StackTeleport.level(server, upper, "the_nether");
                if (target == null) {
                    notice(player, "overworld-top:" + upper.id, "上层世界 " + upper.id
                            + " 加载失败：" + StackDimensionLoader.failureReason(upper.id));
                    return;
                }
                int tx = WorldChainRules.toNether(player.getX());
                int tz = WorldChainRules.toNether(player.getZ());
                teleport(player, target, upper, tx, tz, netherRoofY(target, tx, tz),
                        "主世界顶部 → 上层 " + upper.id + " 下界");
            }
        }
    }

    private static void teleport(ServerPlayer player, ServerLevel target, StackLayer layer,
                                 int x, int z, int y, String reason) {
        teleport(player, target, layer, x, z, y, reason, false);
    }

    private static void teleport(ServerPlayer player, ServerLevel target, StackLayer layer,
                                 int x, int z, int y, String reason, boolean voidDrop) {
        long now = System.currentTimeMillis();
        Long last = lastTeleport.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastTeleport.put(player.getUUID(), now);
        if (voidDrop) {
            // 下界虚空已经累积了很长的摔落距离；不清掉会在下层主世界落地时直接摔死。
            // 速度不在这里清零：下面用 Relative.DELTA 把进入虚空时的动量（含水平与下落）
            // 一并带到落点，避免跨世界传送把玩家“刹停”。
            player.resetFallDistance();
        }
        Set<Relative> relatives = voidDrop ? Relative.DELTA : Set.of();
        player.teleportTo(target, x + 0.5, y, z + 0.5, relatives,
                player.getYRot(), player.getXRot(), false);
        applyRespawn(player, target, layer);
        if (voidDrop) {
            voidDropGuardUntil.put(player.getUUID(), now + VOID_DROP_GUARD_MS);
            applyVoidDropSlowFalling(player);
        }
        player.sendSystemMessage(Component.literal("[世界栈] " + reason
                + " · (" + x + ", " + y + ", " + z + ")"));
        Samsara.LOGGER.info("[周目管理 samsara] 跨世界传送：{} {} → {} ({}, {}, {})",
                player.getName().getString(), reason,
                target.dimension().identifier(), x, y, z);
    }

    /**
     * 计算“下界虚空 → 下层主世界”的高空 Y。
     *
     * <p>先确保目标区块已生成；区块加载失败时降级到地表安全点并写 WARN。
     * 落点 Y 由 {@link WorldChainRules#voidDropY(int, int)} 给出，再从落点向上
     * 找 2~3 格空气，避免落在方块里。找不到空气时同样降级到地表安全点。
     */
    private static int resolveVoidDropY(ServerLevel target, int x, int z, StackLayer lower) {
        try {
            target.getChunk(x >> 4, z >> 4);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 下层世界 {} 目标区块 ({}, {}) 加载失败，"
                            + "虚空落点降级为地表安全点：{}",
                    lower.id, x >> 4, z >> 4, e.getMessage());
            return safeY(target, x, z);
        }
        int baseY = WorldChainRules.voidDropY(
                target.dimensionType().minY(), target.dimensionType().height());
        int airY = findVoidDropAirY(target, x, z, baseY);
        if (airY == Integer.MIN_VALUE) {
            Samsara.LOGGER.warn("[周目管理 samsara] 下层世界 {} ({}, {}) 高空 {} 附近没有空气，"
                            + "虚空落点降级为地表安全点",
                    lower.id, x, z, baseY);
            return safeY(target, x, z);
        }
        return airY;
    }

    /** 从高空落点向上找 2~3 格空气；必须保持在主世界顶部阈值以下。 */
    private static int findVoidDropAirY(ServerLevel level, int x, int z, int baseY) {
        int top = level.dimensionType().minY() + level.dimensionType().height();
        int upper = top - 2;
        for (int i = 0; i < 3; i++) {
            int y = baseY + i;
            if (y > upper) {
                break;
            }
            if (level.getBlockState(new BlockPos(x, y, z)).isAir()
                    && level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 默认给 60 秒无粒子、无环境音、无图标的缓降；可在 commands.yml 关闭或改时长。 */
    private static void applyVoidDropSlowFalling(ServerPlayer player) {
        CommandConfig config = CommandConfig.getInstance();
        if (!config.chainVoidDropSlowFalling()) {
            return;
        }
        int seconds = config.chainVoidDropSlowFallingSeconds();
        if (seconds <= 0) {
            return;
        }
        player.level().registryAccess().lookupOrThrow(Registries.MOB_EFFECT)
                .get(Identifier.parse("minecraft:slow_falling"))
                .ifPresent(effect -> player.addEffect(new MobEffectInstance(
                        effect, seconds * 20, 0, false, false, false)));
    }

    /**
     * 跨栈链也更新个人重生点，让“从未执行 world enter、只靠自动穿越”的玩家
     * 在该层死亡后回到本层出生点，而不是全局主世界。
     *
     * <p>只在该层主世界生效；下界/末地落点不作为重生点（避免重生在屋顶/虚空）。
     * forced=true 是必须的：原版只对床/重生锚使用非强制重生点。
     */
    private static void applyRespawn(ServerPlayer player, ServerLevel target, StackLayer layer) {
        if (layer == null || !"overworld".equals(StackPaths.dimensionOf(target.dimension()))) {
            return;
        }
        int x = layer.spawnX;
        int z = layer.spawnZ;
        int minY = target.dimensionType().minY();
        int maxY = minY + target.dimensionType().height();
        int y = layer.spawnY;
        if (y <= minY || y >= maxY || !isDry(target, x, y, z)) {
            y = safeY(target, x, z);
        }
        LevelData.RespawnData respawn = LevelData.RespawnData.of(
                target.dimension(), new BlockPos(x, y, z), player.getYRot(), player.getXRot());
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawn, true), false);
    }

    private static void notice(ServerPlayer player, String key, String message) {
        String previous = lastNotice.get(player.getUUID());
        if (key.equals(previous)) {
            return;
        }
        lastNotice.put(player.getUUID(), key);
        player.sendSystemMessage(Component.literal("[世界栈] " + message));
    }

    static int safeY(ServerLevel level, int x, int z) {
        try {
            level.getChunk(x >> 4, z >> 4);
        } catch (Exception ignored) {
        }
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (h <= minY || h >= maxY) {
            for (int yy = maxY - 2; yy > minY; yy--) {
                if (level.getBlockState(new BlockPos(x, yy, z)).isSolid()) {
                    h = yy;
                    break;
                }
            }
        }
        int y = Math.max(minY + 2, Math.min(maxY - 2, h + 1));
        for (int i = 0; i < 4; i++) {
            if (isDry(level, x, y, z)) {
                return y;
            }
            y++;
        }
        int found = findDry(level, x, z, 12, minY, maxY);
        if (found != Integer.MIN_VALUE) {
            return found;
        }
        for (int yy = maxY - 3; yy > minY + 1; yy--) {
            if (isDry(level, x, yy, z)) {
                return yy;
            }
        }
        return Math.max(minY + 2, Math.min(maxY - 2, y));
    }

    private static boolean isDry(ServerLevel level, int x, int y, int z) {
        BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
        BlockState feet = level.getBlockState(new BlockPos(x, y, z));
        BlockState head = level.getBlockState(new BlockPos(x, y + 1, z));
        return ground.isSolid()
                && feet.getFluidState().isEmpty() && !feet.isSolid()
                && head.getFluidState().isEmpty() && !head.isSolid();
    }

    private static int findDry(ServerLevel level, int x, int z, int radius, int minY, int maxY) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz);
                    int y = Math.max(minY + 2, Math.min(maxY - 2, h + 1));
                    if (isDry(level, x + dx, y, z + dz)) {
                        return y;
                    }
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    static int netherRoofY(ServerLevel level, int x, int z) {
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        for (int yy = maxY - 2; yy > minY; yy--) {
            if (level.getBlockState(new BlockPos(x, yy, z)).isSolid()) {
                return Math.min(maxY - 2, yy + 1);
            }
        }
        return safeY(level, x, z);
    }

    /** 同层下界落点：优先在屋顶以下找安全地面，避免站到基岩顶。 */
    static int safeNetherY(ServerLevel level, int x, int z) {
        try {
            level.getChunk(x >> 4, z >> 4);
        } catch (Exception ignored) {
        }
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        int cap = Math.min(maxY - 3, minY + 120);
        for (int y = cap; y > minY + 1; y--) {
            if (isDry(level, x, y, z)) {
                return y;
            }
        }
        return Math.min(cap, safeY(level, x, z));
    }
}
