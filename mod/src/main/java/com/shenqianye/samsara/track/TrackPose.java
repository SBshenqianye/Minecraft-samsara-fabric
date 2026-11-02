package com.shenqianye.samsara.track;

/**
 * 轨迹样本 flags 的纯映射（不依赖 Minecraft 运行时，便于单测）。
 * <p>
 * {@link TrackStore} 的 flags 是 1 字节（{@code & 0x7F}），7 个 bit 分别表示
 * 潜行 / 疾跑 / 飞行 / 游泳 / 骑乘 / 受伤 / 死亡。二进制格式（v1）保持不变：
 * 本类只负责把位解析成可读状态，具体 {@code Pose}/shared flag 的落地在
 * {@link TrackMannequin#applyTrackFlags(int)}。
 * <p>
 * 姿态优先级（同一时刻多个 bit 同时置位时）：死亡 &gt; 骑乘 &gt; 飞行 &gt; 游泳 &gt; 潜行 &gt; 站立。
 * 疾跑/受伤不参与姿态选择：疾跑是独立 shared flag，受伤是独立的闪烁状态。
 */
public final class TrackPose {
    /** 可见姿态类别；与 {@code net.minecraft.world.entity.Pose} 的对应在 {@link TrackMannequin}。 */
    public enum Posture {
        STANDING, SNEAK, SWIMMING, FLYING, RIDING, DEAD
    }

    /** 单个样本的 flags 解析结果。 */
    public record State(boolean sneak, boolean sprint, boolean flying, boolean swimming,
                        boolean riding, boolean hurt, boolean dead, Posture posture) {
        public boolean standing() {
            return posture == Posture.STANDING;
        }
    }

    /** v1 flags 只有低 7 位；高位保留位一律忽略，旧文件/脏数据按 0 处理。 */
    private static final int FLAG_MASK = 0x7F;

    private TrackPose() {
    }

    public static State of(int flags) {
        return of(flags, -1);
    }

    /**
     * 带飞行类型解析：{@code flightMode} 为 -1（未知，旧轨迹）/0 none/1 elytra/2 creative。
     * <p>
     * 创造飞行不能被画成鞘翅：当轨迹 flags 带 FLYING 但事件明确记录为 creative/none 时，
     * 姿态退回站立/潜行；只有 elytra 或未知（旧轨迹）才使用 {@link Posture#FLYING}。
     */
    public static State of(int flags, int flightMode) {
        int f = flags & FLAG_MASK;
        boolean sneak = (f & TrackStore.FLAG_SNEAK) != 0;
        boolean sprint = (f & TrackStore.FLAG_SPRINT) != 0;
        boolean flying = (f & TrackStore.FLAG_FLYING) != 0;
        boolean swimming = (f & TrackStore.FLAG_SWIMMING) != 0;
        boolean riding = (f & TrackStore.FLAG_RIDING) != 0;
        boolean hurt = (f & TrackStore.FLAG_HURT) != 0;
        boolean dead = (f & TrackStore.FLAG_DEAD) != 0;
        boolean elytra = flying && (flightMode < 0
                || flightMode == TrackEvents.FLIGHT_ELYTRA);
        Posture posture = dead ? Posture.DEAD
                : riding ? Posture.RIDING
                : elytra ? Posture.FLYING
                : swimming ? Posture.SWIMMING
                : sneak ? Posture.SNEAK
                : Posture.STANDING;
        return new State(sneak, sprint, elytra, swimming, riding, hurt, dead, posture);
    }

    /** 只取姿态（忽略疾跑/受伤）。 */
    public static Posture postureOf(int flags) {
        return of(flags).posture();
    }

    public static Posture postureOf(int flags, int flightMode) {
        return of(flags, flightMode).posture();
    }
}
