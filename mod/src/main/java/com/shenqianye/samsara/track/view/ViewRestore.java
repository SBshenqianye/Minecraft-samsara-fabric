package com.shenqianye.samsara.track.view;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 快照恢复（设计 5.7.4）：所有退出路径共用的<b>幂等</b>入口。
 * <p>
 * 外部干预守卫：若观看者当前坐标已明显偏离本会话最后一次写入点，或维度已被
 * 其他系统改变，则跳过位置恢复（保留外部传送）；若游戏模式已不是本会话设置的
 * 模式，则跳过模式恢复（以其当时状态为准，见《需求文档》F-84 注）。
 */
public final class ViewRestore {
    public static final double MAX_DRIFT = 16.0;

    private ViewRestore() {
    }

    /** 恢复目标（真实玩家 / 单测用的内存实现） */
    public interface Sink {
        ViewSnapshot.Data read();

        void write(ViewSnapshot.Data target, boolean restorePosition, boolean restoreMode);
    }

    /**
     * 外部干预描述。
     *
     * @param expectedMode 本会话主动设置的游戏模式；null = 未改模式
     */
    public record Gate(String lastDimension, double lastX, double lastY, double lastZ,
                       String expectedMode, double maxDrift, boolean allowPosition) {
        public static Gate none() {
            return new Gate(null, 0, 0, 0, null, MAX_DRIFT, true);
        }

        public boolean allowPosition(ViewSnapshot.Data current) {
            if (!allowPosition) {
                return false;
            }
            if (lastDimension == null) {
                return true;
            }
            if (!lastDimension.equals(current.dimension())) {
                return false;
            }
            return current.distanceSq(lastX, lastY, lastZ) <= maxDrift * maxDrift;
        }

        public boolean allowMode(ViewSnapshot.Data current) {
            return expectedMode == null || expectedMode.equals(current.gameMode());
        }
    }

    public record Result(boolean skippedPosition, boolean skippedMode, boolean wrote) {
    }

    /**
     * 幂等恢复：先读当前状态，再按守卫决定是否覆盖位置/模式。
     * 第二次调用时当前状态已等于目标，直接返回 {@code wrote=false}。
     */
    public static Result restore(ViewSnapshot.Data target, Sink sink, Gate gate) {
        ViewSnapshot.Data current = sink.read();
        Gate g = gate == null ? Gate.none() : gate;
        boolean restorePosition = g.allowPosition(current);
        boolean restoreMode = g.allowMode(current);
        if (current.equals(target)) {
            return new Result(!restorePosition, !restoreMode, false);
        }
        sink.write(target, restorePosition, restoreMode);
        return new Result(!restorePosition, !restoreMode, true);
    }

    /** 真实玩家写入器 */
    public static Sink sink(ServerPlayer player) {
        return new Sink() {
            @Override
            public ViewSnapshot.Data read() {
                return ViewSnapshot.read(player);
            }

            @Override
            public void write(ViewSnapshot.Data target, boolean restorePosition, boolean restoreMode) {
                if (restoreMode) {
                    try {
                        GameType type = GameType.valueOf(target.gameMode());
                        if (player.gameMode() != type) {
                            player.setGameMode(type);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                player.getAbilities().flying = target.flying();
                player.getAbilities().mayfly = target.mayfly();
                player.getAbilities().invulnerable = target.invulnerable();
                player.getAbilities().setFlyingSpeed(target.flyingSpeed());
                player.getAbilities().setWalkingSpeed(target.walkingSpeed());
                player.onUpdateAbilities();

                ViewSnapshot.Data current = ViewSnapshot.read(player);
                if (!current.effects().equals(target.effects())) {
                    player.removeAllEffects();
                    var registry = player.level().registryAccess().lookupOrThrow(Registries.MOB_EFFECT);
                    for (ViewSnapshot.EffectState e : target.effects()) {
                        registry.get(Identifier.parse(e.id())).ifPresent(holder ->
                                player.addEffect(new MobEffectInstance(holder, e.duration(),
                                        e.amplifier(), e.ambient(), e.visible(), e.showIcon())));
                    }
                }

                if (restorePosition) {
                    ServerLevel level = findLevel(player.level().getServer(), target.dimension());
                    if (level != null) {
                        player.teleportTo(level, target.x(), target.y(), target.z(), Set.of(),
                                target.yaw(), target.pitch(), false);
                        player.setDeltaMovement(Vec3.ZERO);
                        player.fallDistance = 0;
                        player.resetFallDistance();
                    }
                }
            }
        };
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        if (server == null) {
            return null;
        }
        for (ResourceKey<Level> key : server.levelKeys()) {
            if (key.identifier().toString().equals(dimension)) {
                return server.getLevel(key);
            }
        }
        return null;
    }
}
