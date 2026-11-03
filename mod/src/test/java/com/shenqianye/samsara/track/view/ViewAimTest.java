package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动瞄准：死区、滞回、外圈加速、角度环绕、俯仰开关与单调性。 */
class ViewAimTest {
    private static final ViewAim.Params P = new ViewAim.Params(6, 24, 90, true);

    @Test
    void noCorrectionInsideDeadzone() {
        // 目标在正南 +Z，当前 yaw=0：完全对准
        ViewAim.Delta d = ViewAim.step(0, 0, 0, 64, 0, 0, 64, 10, P, 0.05);
        assertTrue(d.isZero());
    }

    @Test
    void speedGrowsTowardEdgeAndNeverOvershoots() {
        // 目标在正东：期望 yaw = -90；从 yaw=-75 开始，误差 15°
        ViewAim.Delta d = ViewAim.step(-75, 0, 0, 64, 0, 10, 64, 0, P, 0.05);
        double step = Math.hypot(d.yaw(), d.pitch());
        // t=(15-6)/(24-6)=0.5, smoothstep=0.5, v=45°/s, dt=0.05 → 2.25°
        assertEquals(2.25, step, 1e-3);
        assertTrue(step < 15, "不能一次转到位");
    }

    @Test
    void outsideEdgeUsesMaxSpeedButClampsToError() {
        // 目标在正东，当前 yaw=0，误差 90°；max 90°/s × 0.05 = 4.5°
        ViewAim.Delta d = ViewAim.step(0, 0, 0, 64, 0, 10, 64, 0, P, 0.05);
        assertEquals(4.5, Math.hypot(d.yaw(), d.pitch()), 1e-3);
    }

    @Test
    void lowRefreshUsesDiscreteMinimumStep() {
        // 20 tick/s：连续步长只有约 0.52°；低刷新补偿后至少 MIN_STEP_DEG
        ViewAim.Delta d = ViewAim.step(-80, 0, 0, 64, 0, 10, 64, 0, P, 0.05);
        double step = Math.hypot(d.yaw(), d.pitch());
        assertEquals(ViewAim.MIN_STEP_DEG, step, 1e-6);
        assertEquals(0.0, step % ViewAim.STEP_QUANTUM_DEG, 1e-9, "单步应对齐到固定粒度");

        // 5 tick/s（dt=0.2）：最小步按 dt 放大到 4°，不会因为刷新低而变成碎步
        ViewAim.Delta slow = ViewAim.step(-80, 0, 0, 64, 0, 10, 64, 0, P, 0.2);
        assertEquals(4.0, Math.hypot(slow.yaw(), slow.pitch()), 1e-6);
    }

    @Test
    void wrapsAcrossNorth() {
        assertEquals(-2, ViewAim.wrapDegrees(358), 1e-9);
        assertEquals(2, ViewAim.wrapDegrees(-358), 1e-9);
        // 目标在正北：期望 yaw=180；从 170 出发误差 10°，不能走 -350° 的长边
        ViewAim.Delta d = ViewAim.step(170, 0, 0, 64, 0, 0, 64, -10, P, 0.05);
        assertTrue(d.yaw() > 0, "应朝 180° 的短边转");
        assertEquals(ViewAim.MIN_STEP_DEG, Math.hypot(d.yaw(), d.pitch()), 1e-3,
                "低刷新补偿后只应转一个最小步，不能甩到目标");
    }

    @Test
    void pitchCanBeDisabled() {
        ViewAim.Params noPitch = new ViewAim.Params(6, 24, 90, false);
        ViewAim.Delta d = ViewAim.step(0, 0, 0, 64, 0, 10, 74, 30, noPitch, 0.05);
        assertEquals(0f, d.pitch(), 1e-6);
        assertTrue(Math.abs(d.yaw()) > 0);
    }

    @Test
    void hysteresisKeepsAimingInsideOuterDeadzoneUntilInnerCircle() {
        // 目标在正东；当前 yaw=-87，误差 3°。未进入时 3° < 6° 不动。
        ViewAim.Tracker tracker = new ViewAim.Tracker(P);
        assertTrue(tracker.step(-87, 0, 0, 64, 0, 10, 64, 0, 0.05).isZero());
        assertFalse(tracker.engaged());

        // 先把误差拉到 10°：进入
        ViewAim.Delta enter = tracker.step(-80, 0, 0, 64, 0, 10, 64, 0, 0.05);
        assertFalse(enter.isZero());
        assertTrue(tracker.engaged());

        // 误差 4°：在死区(6°)内，但仍在退出内圈(3°)外，应继续修正
        ViewAim.Delta inside = tracker.step(-86, 0, 0, 64, 0, 10, 64, 0, 0.05);
        assertFalse(inside.isZero());
        assertTrue(tracker.engaged());

        // 误差 2°：进入内圈，停止并解除
        ViewAim.Delta exit = tracker.step(-88, 0, 0, 64, 0, 10, 64, 0, 0.05);
        assertTrue(exit.isZero());
        assertFalse(tracker.engaged());

        // 再次回到 4°：因为未重新越过外圈 6°，不应重新启动
        assertTrue(tracker.step(-86, 0, 0, 64, 0, 10, 64, 0, 0.05).isZero());
    }

    @Test
    void angleDecreasesMonotonicallyAndNeverOscillates() {
        ViewAim.Tracker tracker = new ViewAim.Tracker(new ViewAim.Params(4, 30, 120, false));
        float yaw = -120f; // 目标 -90°，误差 30°
        double previousError = 30.0;
        for (int i = 0; i < 200; i++) {
            ViewAim.Delta d = tracker.step(yaw, 0, 0, 64, 0, 10, 64, 0, 0.05);
            yaw += d.yaw();
            double error = Math.abs(-90.0 - yaw);
            assertTrue(error <= previousError + 1e-6, "误差必须单调不增，不能抖动");
            previousError = error;
            if (error <= ViewAim.MIN_ERROR_DEG) {
                break;
            }
        }
        assertTrue(previousError <= new ViewAim.Params(4, 30, 120, false).exitDeadzone(),
                "最终应稳定在内圈（滞回退出点）以内");
        // 稳定后连续多次调用都不应再动
        for (int i = 0; i < 5; i++) {
            assertTrue(tracker.step(yaw, 0, 0, 64, 0, 10, 64, 0, 0.05).isZero());
        }
    }

    @Test
    void pitchIsMoreConservativeThanYaw() {
        // 水平与俯仰各有 20° 误差；俯仰增量应明显更小
        ViewAim.Delta d = ViewAim.step(-70, -20, 0, 64, 0, 10, 64, 20, P, 0.05);
        assertTrue(Math.abs(d.pitch()) < Math.abs(d.yaw()));
    }
}
