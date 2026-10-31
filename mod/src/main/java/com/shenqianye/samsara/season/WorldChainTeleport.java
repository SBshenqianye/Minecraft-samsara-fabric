package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跨世界传送链（F-29~F-34）：主世界/下界虚空与顶部阈值触发，支持连续穿越世界栈。
 *
 * <p>同世界内的下界往返只依赖原版维度；跨栈层需要 {@link MultiWorldLoader} 同时加载其它层。
 * 如果额外层加载失败，跨层传送不会静默跳过：会给玩家明确提示，并把原因写进日志/状态。
 *
 * <p>防抖：每位玩家传送后 3 秒冷却 + 目标点安全 Y，避免一次下落连续触发。
 * 保留下界传送门，不替代原版。
 */
public final class WorldChainTeleport {
    private static final int CHECK_INTERVAL_TICKS = 5;
    private static final long COOLDOWN_MS = 3000L;

    private static Map<String, MultiWorldLoader.LoadedWorld> loaded = Map.of();
    private static final Map<UUID, Long> lastTeleport = new HashMap<>();
    private static final Map<UUID, String> lastNotice = new HashMap<>();
    private static int counter;

    private WorldChainTeleport() {
    }

    public static synchronized void load(MinecraftServer server) {
        unload();
        List<String> stack = WorldStackManager.getInstance().stack();
        String current = WorldStackManager.currentWorldName(server);
        if (stack.size() <= 1) {
            loaded = Map.of();
            return;
        }
        if (Boolean.getBoolean("samsara.multiWorld.disabled")) {
            loaded = Map.of();
            Samsara.LOGGER.info("[周目管理 samsara] 跨世界额外层加载已被 -Dsamsara.multiWorld.disabled 关闭");
            return;
        }
        loaded = MultiWorldLoader.loadOthers(server, stack, current);
        Samsara.LOGGER.info("[周目管理 samsara] {}", MultiWorldLoader.status(loaded));
    }

    public static synchronized void unload() {
        for (MultiWorldLoader.LoadedWorld world : loaded.values()) {
            world.close();
        }
        loaded = Map.of();
        lastTeleport.clear();
        lastNotice.clear();
    }

    public static synchronized String statusText() {
        return MultiWorldLoader.status(loaded);
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
        String worldName = worldNameOf(server, level);
        String dimension = dimensionOf(server, level);
        if (worldName == null || dimension == null) {
            return;
        }
        int minY = level.dimensionType().minY();
        double y = player.getY();
        if (WorldChainRules.isVoid(y, minY)) {
            if ("overworld".equals(dimension)) {
                // F-29：主世界虚空 → 同世界下界
                ServerLevel nether = target(server, worldName, "the_nether");
                if (nether != null) {
                    int tx = WorldChainRules.toNether(player.getX());
                    int tz = WorldChainRules.toNether(player.getZ());
                    teleport(player, nether, tx, tz, safeY(nether, tx, tz),
                            "虚空坠落 → 同世界下界");
                }
            } else if ("the_nether".equals(dimension)) {
                // F-30/F-34：下界虚空 → 下层世界主世界；栈底交给原版虚空死亡
                String lower = lowerWorld(server, worldName);
                if (lower == null) {
                    return; // F-34：栈底下界再往下掉 = 真正死亡
                }
                ServerLevel target = target(server, lower, "overworld");
                if (target == null) {
                    notice(player, "nether-void", "下层世界 " + lower
                            + " 未被额外加载，无法跨世界传送（请查看 /samsara debug world multi）");
                    return;
                }
                int tx = WorldChainRules.toOverworld(player.getX());
                int tz = WorldChainRules.toOverworld(player.getZ());
                teleport(player, target, tx, tz, safeY(target, tx, tz),
                        "下界虚空 → 下层世界 " + lower + " 主世界");
            }
            return;
        }
        if (WorldChainRules.isTop(y, minY, level.dimensionType().height(), "the_nether".equals(dimension) ? WorldChainRules.DimensionKind.NETHER : WorldChainRules.DimensionKind.OVERWORLD)) {
            if ("the_nether".equals(dimension)) {
                // F-31：下界顶部 → 同世界主世界
                ServerLevel overworld = target(server, worldName, "overworld");
                if (overworld != null) {
                    int tx = WorldChainRules.toOverworld(player.getX());
                    int tz = WorldChainRules.toOverworld(player.getZ());
                    teleport(player, overworld, tx, tz, safeY(overworld, tx, tz),
                            "下界顶部 → 同世界主世界");
                }
            } else if ("overworld".equals(dimension)) {
                // F-32：主世界顶部 → 上层世界下界
                String upper = upperWorld(server, worldName);
                if (upper == null) {
                    return;
                }
                ServerLevel target = target(server, upper, "the_nether");
                if (target == null) {
                    notice(player, "overworld-top", "上层世界 " + upper
                            + " 未被额外加载，无法跨世界传送（请查看 /samsara debug world multi）");
                    return;
                }
                int tx = WorldChainRules.toNether(player.getX());
                int tz = WorldChainRules.toNether(player.getZ());
                teleport(player, target, tx, tz, netherRoofY(target, tx, tz),
                        "主世界顶部 → 上层世界 " + upper + " 下界");
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

    private static String lowerWorld(MinecraftServer server, String worldName) {
        List<String> stack = WorldStackManager.getInstance().stack();
        int index = stack.indexOf(worldName);
        return index > 0 ? stack.get(index - 1) : null;
    }

    private static String upperWorld(MinecraftServer server, String worldName) {
        List<String> stack = WorldStackManager.getInstance().stack();
        int index = stack.indexOf(worldName);
        return index >= 0 && index + 1 < stack.size() ? stack.get(index + 1) : null;
    }

    private static ServerLevel target(MinecraftServer server, String worldName, String dimension) {
        String current = WorldStackManager.currentWorldName(server);
        if (worldName.equals(current)) {
            return server.getLevel(dimensionKey(dimension));
        }
        MultiWorldLoader.LoadedWorld world = loaded.get(worldName);
        return world == null ? null : world.levels.get(dimension);
    }

    private static ResourceKey<Level> dimensionKey(String dimension) {
        return switch (dimension) {
            case "the_nether" -> Level.NETHER;
            case "the_end" -> Level.END;
            default -> Level.OVERWORLD;
        };
    }

    private static String worldNameOf(MinecraftServer server, ServerLevel level) {
        String current = WorldStackManager.currentWorldName(server);
        if (level == server.getLevel(Level.OVERWORLD)
                || level == server.getLevel(Level.NETHER)
                || level == server.getLevel(Level.END)) {
            return current;
        }
        return MultiWorldLoader.worldNameOf(loaded, level);
    }

    private static String dimensionOf(MinecraftServer server, ServerLevel level) {
        if (level == server.getLevel(Level.OVERWORLD)) {
            return "overworld";
        }
        if (level == server.getLevel(Level.NETHER)) {
            return "the_nether";
        }
        if (level == server.getLevel(Level.END)) {
            return "the_end";
        }
        return MultiWorldLoader.dimensionOf(loaded, level);
    }

    /** 安全落脚 Y：强制加载区块，避开海洋/岩浆，异常高度从顶部往下扫。 */
    static int safeY(ServerLevel level, int x, int z) {
        try {
            level.getChunk(x >> 4, z >> 4);
        } catch (Exception e) {
            // 生成失败时退回原版高度图
        }
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (h <= minY || h >= maxY) {
            for (int yy = maxY - 2; yy > minY; yy--) {
                BlockState state = level.getBlockState(new BlockPos(x, yy, z));
                if (state.isSolid()) {
                    h = yy;
                    break;
                }
            }
        }
        int y = Math.max(minY + 2, Math.min(maxY - 2, h + 1));
        // 目标列是水/岩浆/实心方块：就近找一个能站两人的干地
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
        // 实在找不到：从顶部往下找第一处「脚下实心、头顶两格空」的位置
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

    /** 下界顶部目标：基岩顶上方一格；没有基岩则取地表。 */
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
}
