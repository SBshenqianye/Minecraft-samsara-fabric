package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

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

    private static final Map<UUID, Long> lastTeleport = new HashMap<>();
    private static final Map<UUID, String> lastNotice = new HashMap<>();
    private static int counter;

    private WorldChainTeleport() {
    }

    public static synchronized void load(MinecraftServer server) {
        Samsara.LOGGER.info("[周目管理 samsara] {}", StackDimensionLoader.status());
    }

    public static synchronized void unload() {
        lastTeleport.clear();
        lastNotice.clear();
    }

    public static synchronized String statusText() {
        return StackDimensionLoader.status();
    }

    public static synchronized void tick(MinecraftServer server) {
        if (++counter < CHECK_INTERVAL_TICKS) {
            return;
        }
        counter = 0;
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
                    teleport(player, nether, tx, tz, safeNetherY(nether, tx, tz),
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
                teleport(player, target, tx, tz, safeY(target, tx, tz),
                        "下界虚空 → 下层 " + lower.id + " 主世界");
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
                    teleport(player, overworld, tx, tz, safeY(overworld, tx, tz),
                            "下界顶部 → 同层主世界");
                } else {
                    notice(player, "top-overworld:" + layer.id, "同层主世界（" + layer.id
                            + "）加载失败：" + StackDimensionLoader.failureReason(layer.id));
                }
            } else if ("overworld".equals(dim)) {
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
                teleport(player, target, tx, tz, netherRoofY(target, tx, tz),
                        "主世界顶部 → 上层 " + upper.id + " 下界");
            }
        }
    }

    private static void teleport(ServerPlayer player, ServerLevel target,
                                 int x, int z, int y, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastTeleport.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastTeleport.put(player.getUUID(), now);
        player.teleportTo(target, x + 0.5, y, z + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal("[世界栈] " + reason
                + " · (" + x + ", " + y + ", " + z + ")"));
        Samsara.LOGGER.info("[周目管理 samsara] 跨世界传送：{} {} → {} ({}, {}, {})",
                player.getName().getString(), reason,
                target.dimension().identifier(), x, y, z);
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
