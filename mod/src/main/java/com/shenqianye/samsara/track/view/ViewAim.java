package com.shenqianye.samsara.track.view;

/**
 * 回看自动瞄准（设计〈自动瞄准〉/ F-100）：纯数学，不依赖 Minecraft。
 * <p>
 * 采用“外圈进入、内圈退出”的滞回死区：误差大于 {@code deadzone} 才开始修正；
 * 已经进入后，误差降到内圈（默认死区的一半）以下才停止。这样既不会在边界来回抖动，
 * 也不会把观众的头一直吸在目标上。
 * <p>
 * 角速度按 smoothstep 从 0 平滑升到 {@code maxDegPerSec}，每 tick 只走一小步且绝不越过目标；
 * 俯仰修正额外乘 {@link #PITCH_SCALE}，比水平方向更保守，减少晕动。
 * <p>
 * “圈”只是抽象概念，服务端不渲染 HUD。
 */
public final class ViewAim {
    /** 低刷新率补偿：误差大于死区时，单次至少转过的角度（度）。 */
    public static final double MIN_STEP_DEG = 1.0;
    /** 低刷新率补偿的基准 tick 间隔（秒，20 tick/s）。 */
    public static final double REFERENCE_TICK_SECONDS = 0.05;
    /** 单步量化粒度（度）：让低刷新率下的视角变化更稳定。 */
    public static final double STEP_QUANTUM_DEG = 0.25;
    /** 进入后退出死区的比例：deadzone × 0.5。 */
    public static final double EXIT_RATIO = 0.5;
    /** 小于该角度视为手抖/已对准，直接停止。 */
    public static final double MIN_ERROR_DEG = 0.5;
    /** 俯仰修正相对水平方向的缩放（越小越保守）。 */
    public static final double PITCH_SCALE = 0.5;

    /** 参数；构造时校验，非法值直接抛 {@link IllegalArgumentException}。 */
    public record Params(double deadzone, double edge, double maxDegPerSec, boolean pitch) {
        public Params {
            if (deadzone < 2 || deadzone > 45) {
                throw new IllegalArgumentException("自动瞄准内圈须在 2..45 度内: " + deadzone);
            }
            if (edge <= deadzone || edge > 75) {
                throw new IllegalArgumentException("自动瞄准外圈须大于内圈且不超过 75 度: " + edge);
            }
            if (maxDegPerSec < 10 || maxDegPerSec > 360) {
                throw new IllegalArgumentException("自动瞄准角速度须在 10..360 度/秒内: " + maxDegPerSec);
            }
        }

        public double exitDeadzone() {
            return Math.max(MIN_ERROR_DEG, deadzone * EXIT_RATIO);
        }
    }

    /** 本 tick 需要追加的视角增量（度）。 */
    public record Delta(float yaw, float pitch) {
        public boolean isZero() {
            return Math.abs(yaw) < 0.001f && Math.abs(pitch) < 0.001f;
        }
    }

    /**
     * 有状态的自瞄器：每个回看会话一个，保存滞回“已进入”状态。
     * 跳转/切镜头/重新开启自瞄时调用 {@link #reset()}。
     */
    public static final class Tracker {
        private final Params params;
        private boolean engaged;

        public Tracker(Params params) {
            this.params = params;
        }

        public Params params() {
            return params;
        }

        public boolean engaged() {
            return engaged;
        }

        public void reset() {
            engaged = false;
        }

        /**
         * 计算一 tick 的视角增量。
         *
         * @param currentYaw   当前 yaw（度）
         * @param currentPitch 当前 pitch（度）
         * @param eyeX         观看者眼睛 X
         * @param eyeY         观看者眼睛 Y
         * @param eyeZ         观看者眼睛 Z
         * @param targetX      人偶观察点 X
         * @param targetY      人偶观察点 Y（建议眼睛高度）
         * @param targetZ      人偶观察点 Z
         * @param dtSeconds    距上一 tick 的秒数（调用方钳到 0..0.25）
         */
        public Delta step(float currentYaw, float currentPitch,
                          double eyeX, double eyeY, double eyeZ,
                          double targetX, double targetY, double targetZ,
                          double dtSeconds) {
            if (dtSeconds <= 0) {
                return new Delta(0f, 0f);
            }
            double dx = targetX - eyeX;
            double dy = targetY - eyeY;
            double dz = targetZ - eyeZ;
            double horizontal = Math.hypot(dx, dz);
            if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
                engaged = false;
                return new Delta(0f, 0f);
            }
            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

            double errorYaw = wrapDegrees(desiredYaw - currentYaw);
            double errorPitch = params.pitch()
                    ? wrapDegrees(desiredPitch - currentPitch) * PITCH_SCALE : 0.0;
            double error = Math.hypot(errorYaw, errorPitch);

            // 外圈进入 / 内圈退出；进入后允许在死区内继续走完，避免边界抖动
            if (!engaged) {
                if (error <= params.deadzone() || error <= MIN_ERROR_DEG) {
                    return new Delta(0f, 0f);
                }
                engaged = true;
            } else if (error <= params.exitDeadzone() || error <= MIN_ERROR_DEG) {
                engaged = false;
                return new Delta(0f, 0f);
            }

            double t = params.edge() <= params.deadzone() ? 1.0
                    : clamp01((error - params.deadzone()) / (params.edge() - params.deadzone()));
            double speed = params.maxDegPerSec() * smoothstep(t);
            double step = Math.min(error, speed * dtSeconds);
            // 低刷新率补偿：刷新越慢，单次至少转过的角度越大，保持可感知的角速度；
            // 同时不超过配置的最大角速度。再对齐到固定粒度，最后钳到剩余误差，
            // 绝不越过目标，避免来回抖动（头晕）。
            double minStep = Math.min(
                    MIN_STEP_DEG * Math.max(1.0, dtSeconds / REFERENCE_TICK_SECONDS),
                    params.maxDegPerSec() * dtSeconds);
            step = Math.max(step, minStep);
            step = quantizeUp(step, STEP_QUANTUM_DEG);
            step = Math.min(error, step);
            double fraction = step / error;
            return new Delta((float) (errorYaw * fraction), (float) (errorPitch * fraction));
        }
    }

    private ViewAim() {
    }

    /**
     * 无状态便捷入口（单次计算；需要滞回请使用 {@link Tracker}）。
     * 每次调用都从“未进入”开始，因此等价于“误差大于死区才动”。
     */
    public static Delta step(float currentYaw, float currentPitch,
                             double eyeX, double eyeY, double eyeZ,
                             double targetX, double targetY, double targetZ,
                             Params params, double dtSeconds) {
        if (params == null) {
            return new Delta(0f, 0f);
        }
        return new Tracker(params).step(currentYaw, currentPitch,
                eyeX, eyeY, eyeZ, targetX, targetY, targetZ, dtSeconds);
    }

    /** 把角度归一化到 [-180, 180)。 */
    /**
     * 快速对准（snap）：直接返回当前角误差，由上层按 {@code view.aim.hz} 节流调用。
     * <p>
     * 与 {@link #step} 的区别是不做速度积分、不经过死区滞回，观感是“瞬间看过去”，
     * 避免缓慢拖拽造成的头晕；俯仰仍按 {@link #PITCH_SCALE} 缩放。
     */
    public static Delta snap(float currentYaw, float currentPitch,
                             double eyeX, double eyeY, double eyeZ,
                             double targetX, double targetY, double targetZ,
                             boolean includePitch) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            return new Delta(0f, 0f);
        }
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        double errorYaw = wrapDegrees(desiredYaw - currentYaw);
        double errorPitch = includePitch
                ? wrapDegrees(desiredPitch - currentPitch) * PITCH_SCALE : 0.0;
        return new Delta((float) errorYaw, (float) errorPitch);
    }

    public static double wrapDegrees(double degrees) {
        double v = degrees % 360.0;
        if (v >= 180.0) {
            v -= 360.0;
        } else if (v < -180.0) {
            v += 360.0;
        }
        return v;
    }

    /** 0→0，1→1，两端导数为 0 的平滑曲线。 */
    public static double smoothstep(double t) {
        double x = clamp01(t);
        return x * x * (3.0 - 2.0 * x);
    }

    /** 向上对齐到 {@code quantum} 的整数倍（{@code quantum <= 0} 时原样返回）。 */
    static double quantizeUp(double value, double quantum) {
        if (quantum <= 0) {
            return value;
        }
        return Math.ceil(value / quantum) * quantum;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
