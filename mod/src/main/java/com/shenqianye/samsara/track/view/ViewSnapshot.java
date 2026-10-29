package com.shenqianye.samsara.track.view;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * 观看者状态快照（设计 5.7.4 / F-84）。
 * <p>
 * 只保存"回看会改动/需要还原"的字段：维度、坐标、朝向、速度、下落距离、
 * 游戏模式、能力、临时状态效果。效果以注册表 id 保存，便于纯 Java 单测。
 */
public final class ViewSnapshot {
    /** 一条状态效果（纯数据） */
    public record EffectState(String id, int duration, int amplifier,
                              boolean ambient, boolean visible, boolean showIcon) {
    }

    /** 快照数据；不可变，可直接用于幂等性比较 */
    public record Data(String dimension,
                       double x, double y, double z,
                       float yaw, float pitch,
                       double vx, double vy, double vz,
                       double fallDistance,
                       String gameMode,
                       boolean flying, boolean mayfly, boolean invulnerable,
                       float flyingSpeed, float walkingSpeed,
                       List<EffectState> effects) {
        public Data {
            effects = List.copyOf(effects);
        }

        public double distanceSq(double ox, double oy, double oz) {
            double dx = x - ox;
            double dy = y - oy;
            double dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Data data;

    private ViewSnapshot(Data data) {
        this.data = data;
    }

    public static ViewSnapshot capture(ServerPlayer player) {
        return new ViewSnapshot(read(player));
    }

    public Data data() {
        return data;
    }

    /** 从在线玩家读取当前状态（也供 {@link ViewRestore.Sink} 使用） */
    public static Data read(ServerPlayer player) {
        List<EffectState> effects = new ArrayList<>();
        for (MobEffectInstance e : player.getActiveEffects()) {
            String id = e.getEffect().unwrapKey()
                    .map(k -> k.identifier().toString())
                    .orElse("minecraft:unknown");
            effects.add(new EffectState(id, e.getDuration(), e.getAmplifier(),
                    e.isAmbient(), e.isVisible(), e.showIcon()));
        }
        return new Data(
                player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z,
                player.fallDistance,
                player.gameMode().name(),
                player.getAbilities().flying,
                player.getAbilities().mayfly,
                player.getAbilities().invulnerable,
                player.getAbilities().getFlyingSpeed(),
                player.getAbilities().getWalkingSpeed(),
                effects);
    }
}
