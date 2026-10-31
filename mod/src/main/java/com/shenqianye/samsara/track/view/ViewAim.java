package com.shenqianye.samsara.track.view;

/**
 * 回看自动瞄准（设计〈自动瞄准〉/ F-100）：纯数学，不依赖 Minecraft。
 * <p>
 * 以视线中心为内圈死区：误差小于 {@code deadzone} 不修正；越接近 {@code edge}
 * 修正速度越快；到外圈及以外钳到 {@code maxDegPerSec}。每 tick 只走一小步，
 * 由调用方把 {@link Delta} 作为旋转增量发给客户端。
 * <p>
 * “圈”只是抽象概念，服务端不渲染 HUD。
 */
public final class ViewAim {
    /**
     * 低刷新率补偿：误差大于死区时，单次至少转过的角度（度）。
     * <p>
     * 以 20 tick/s 为基准：刷新变慢时按 {@code dt / 0.05} 放大最小步，
     * 保证低刷新率下仍有可感知的角速度，而不是每次只挪零点几度。
     */
    public static final double MIN_STEP_DEG = 2.0;
    /** 低刷新率补偿的基准 tick 间隔（秒，20 tick/s）。 */
    public static final double REFERENCE_TICK_SECONDS = 0.05;
    /**
     * 单步量化粒度（度）：把每 tick 的转动量对齐到固定档位，
     * 让低刷新率下的视角变化更“离散、稳定”，而不是忽大忽小。
     */
    public static final double STEP_QUANTUM_DEG = 0.5;

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
    }

    /** 本 tick 需要追加的视角增量（度）。 */
    public record Delta(float yaw, float pitch) {
        public boolean isZero() {
            return Math.abs(yaw) < 0.001f && Math.abs(pitch) < 0.001f;
        }
    }

    private ViewAim() {
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
     * @param params       死区/外圈/角速度
     * @param dtSeconds    距上一 tick 的秒数（调用方钳到 0..0.25）
     */
    public static Delta step(float currentYaw, float currentPitch,
                             double eyeX, double eyeY, double eyeZ,
                             double targetX, double targetY, double targetZ,
                             Params params, double dtSeconds) {
        if (params == null || dtSeconds <= 0) {
            return new Delta(0f, 0f);
        }
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
        double errorPitch = params.pitch() ? desiredPitch - currentPitch : 0;
        double error = Math.hypot(errorYaw, errorPitch);
        if (error <= params.deadzone()) {
            return new Delta(0f, 0f);
        }

        double t = params.edge() <= params.deadzone() ? 1.0
                : clamp01((error - params.deadzone()) / (params.edge() - params.deadzone()));
        double speed = params.maxDegPerSec() * smoothstep(t);
        double step = Math.min(error, speed * dtSeconds);
        // 低刷新率补偿：刷新越慢（dt 越大），单次至少转过的角度越大，保持可感知的角速度；
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

    /** 把角度归一化到 [-180, 180)。 */
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
