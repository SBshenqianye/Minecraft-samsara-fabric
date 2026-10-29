package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动瞄准：死区、外圈加速、角度环绕、俯仰开关。 */
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
    void wrapsAcrossNorth() {
        assertEquals(-2, ViewAim.wrapDegrees(358), 1e-9);
        assertEquals(2, ViewAim.wrapDegrees(-358), 1e-9);
        // 目标在正北：期望 yaw=180；从 170 出发误差 10°，不能走 -350° 的长边
        ViewAim.Delta d = ViewAim.step(170, 0, 0, 64, 0, 0, 64, -10, P, 0.05);
        assertTrue(d.yaw() > 0, "应朝 180° 的短边转");
        assertTrue(Math.hypot(d.yaw(), d.pitch()) < 2.0, "只应转一小步");
    }

    @Test
    void pitchCanBeDisabled() {
        ViewAim.Params noPitch = new ViewAim.Params(6, 24, 90, false);
        ViewAim.Delta d = ViewAim.step(0, 0, 0, 64, 0, 10, 74, 30, noPitch, 0.05);
        assertEquals(0f, d.pitch(), 1e-6);
        assertTrue(Math.abs(d.yaw()) > 0);
    }
}
