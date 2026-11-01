package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.util.Set;

/** `world enter/leave` 与跨世界传送共用的栈维度传送。 */
public final class StackTeleport {
    private StackTeleport() {
    }

    public static String enter(CommandSourceStack source, String targetId) {
        MinecraftServer server = source.getServer();
        StackLayer target = find(server, targetId);
        if (target == null) {
            return "世界 " + targetId + " 不在栈中；可用 /samsara world list 查看";
        }
        ServerLevel level = level(server, target, "overworld");
        if (level == null) {
            return "栈世界 " + targetId + " 加载失败：" + StackDimensionLoader.failureReason(targetId);
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            return "请在游戏内由玩家执行，或使用 /execute as <玩家> run ...";
        }
        move(server, player, target, level, target.spawnX, target.spawnZ);
        return "已进入 " + target.label() + " · " + level.dimension().identifier();
    }

    public static String leave(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            return "请在游戏内由玩家执行";
        }
        StackLayer current = layerOf(server, player);
        if (current == null) {
            return "当前维度不是 samsara 栈世界";
        }
        StackManifest manifest = manifest(server);
        StackLayer below = manifest.below(current);
        if (below == null) {
            return "已经在栈底 " + current.id;
        }
        ServerLevel level = level(server, below, "overworld");
        if (level == null) {
            return "下层世界 " + below.id + " 加载失败：" + StackDimensionLoader.failureReason(below.id);
        }
        move(server, player, below, level, below.spawnX, below.spawnZ);
        return "已返回 " + below.label() + " · " + level.dimension().identifier();
    }

    /** 控制台/调试：把指定玩家送到某层。 */
    public static String enter(ServerPlayer player, String targetId) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return "服务器不可用";
        }
        StackLayer target = find(server, targetId);
        if (target == null) {
            return "世界 " + targetId + " 不在栈中";
        }
        ServerLevel level = level(server, target, "overworld");
        if (level == null) {
            return "栈世界 " + target.id + " 加载失败：" + StackDimensionLoader.failureReason(target.id);
        }
        move(server, player, target, level, target.spawnX, target.spawnZ);
        return "已进入 " + target.label();
    }

    public static StackLayer find(MinecraftServer server, String id) {
        return manifest(server).byId(id);
    }

    public static StackManifest manifest(MinecraftServer server) {
        return StackManifest.load(StackPaths.worldDir(server));
    }

    public static StackLayer layerOf(MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return null;
        }
        String id = StackPaths.layerIdOf(player.level().dimension());
        return id == null ? null : manifest(server).byId(id);
    }

    public static ServerLevel level(MinecraftServer server, StackLayer layer, String dim) {
        if (layer == null) {
            return null;
        }
        if (layer.top) {
            return server.getLevel(switch (dim) {
                case "the_nether" -> Level.NETHER;
                case "the_end" -> Level.END;
                default -> Level.OVERWORLD;
            });
        }
        ServerLevel level = StackDimensionLoader.level(layer, dim);
        if (level != null) {
            return level;
        }
        // 目标栈层启动时加载失败：按需自动加载/重试，失败原因由调用方展示。
        return StackDimensionLoader.ensureLevel(server, layer, dim);
    }

    private static void move(MinecraftServer server, ServerPlayer player, StackLayer layer,
                             ServerLevel level, int x, int z) {
        int y = layer.top ? topSpawnY(level, x, z) : layer.spawnY;
        if (y <= level.dimensionType().minY() || !isSafeStand(level, x, y, z)) {
            y = safeY(level, x, z);
        }
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), false);
        // 个人重生点：在没有床/锚时回到当前栈的出生点，而不是全局主世界。
        // forced=true 是必须的：原版只对床/重生锚使用非强制重生点；普通地面 spawn
        // 若 forced=false 会在死亡时被判为“没有床”，回退到全局主世界。
        LevelData.RespawnData respawn = LevelData.RespawnData.of(
                level.dimension(), new BlockPos(x, y, z), player.getYRot(), player.getXRot());
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawn, true), false);
        player.sendSystemMessage(Component.literal("[世界栈] 已进入 " + layer.label()
                + " · 出生点 (" + x + ", " + y + ", " + z + ")"));
        Samsara.LOGGER.info("[周目管理 samsara] {} 进入栈层 {} @ {}",
                player.getName().getString(), layer.id, level.dimension().identifier());
    }

    private static int topSpawnY(ServerLevel level, int x, int z) {
        BlockPos spawn = level.getRespawnData().pos();
        return spawn.getY();
    }

    private static int safeY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return Math.max(level.dimensionType().minY() + 2,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1);
    }

    private static boolean isSafeStand(ServerLevel level, int x, int y, int z) {
        try {
            level.getChunk(x >> 4, z >> 4);
        } catch (Exception e) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        var ground = level.getBlockState(feet.below());
        var at = level.getBlockState(feet);
        var head = level.getBlockState(feet.above());
        return ground.isSolid() && at.getFluidState().isEmpty() && !at.isSolid()
                && head.getFluidState().isEmpty() && !head.isSolid();
    }
}
