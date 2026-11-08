package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackSamplingPolicyTest {
    private static final TrackSamplingPolicy.Config ADAPTIVE =
            new TrackSamplingPolicy.Config(true, 5, 3, 5, 3_000, 10_000, 60_000);
    private static final TrackSamplingPolicy.Config FIXED =
            new TrackSamplingPolicy.Config(false, 5, 3, 10, 3_000, 10_000, 60_000);

    @Test
    void idleWritesHeartbeatOnly() {
        TrackSamplingPolicy.Decision early = TrackSamplingPolicy.decide(
                true, 1_000, 1_000, 0, false, 11_000, ADAPTIVE);
        assertFalse(early.write());
        assertFalse(early.heartbeat());
        assertEquals("idle", early.tier());

        TrackSamplingPolicy.Decision heartbeat = TrackSamplingPolicy.decide(
                true, 1_000, 1_000, 0, false, 61_500, ADAPTIVE);
        assertTrue(heartbeat.write());
        assertTrue(heartbeat.heartbeat());
    }

    @Test
    void pureMovementUsesMoveHz() {
        TrackSamplingPolicy.Decision at200 = TrackSamplingPolicy.decide(
                false, 0, 1_000, 0, false, 1_200, ADAPTIVE);
        assertFalse(at200.write());
        assertEquals("move", at200.tier());
        assertEquals(3.0, at200.hz(), 0.0001);

        TrackSamplingPolicy.Decision at340 = TrackSamplingPolicy.decide(
                false, 0, 1_000, 0, false, 1_340, ADAPTIVE);
        assertTrue(at340.write());
    }

    @Test
    void combatWindowAndVehicleUseCombatHz() {
        TrackSamplingPolicy.Decision combat = TrackSamplingPolicy.decide(
                false, 0, 1_000, 2_500, false, 1_200, ADAPTIVE);
        assertTrue(combat.write());
        assertEquals("combat", combat.tier());
        assertEquals(5.0, combat.hz(), 0.0001);

        TrackSamplingPolicy.Decision expired = TrackSamplingPolicy.decide(
                false, 0, 1_000, 2_500, false, 3_000, ADAPTIVE);
        assertEquals("move", expired.tier());

        TrackSamplingPolicy.Decision vehicle = TrackSamplingPolicy.decide(
                false, 0, 1_000, 0, true, 1_200, ADAPTIVE);
        assertEquals("vehicle", vehicle.tier());
        assertTrue(vehicle.write());
    }

    @Test
    void adaptiveDisabledRestoresFixedBaseHz() {
        TrackSamplingPolicy.Decision at200 = TrackSamplingPolicy.decide(
                false, 0, 1_000, 0, false, 1_150, FIXED);
        assertFalse(at200.write());
        assertEquals("fixed", at200.tier());
        assertEquals(5.0, at200.hz(), 0.0001);

        TrackSamplingPolicy.Decision at400 = TrackSamplingPolicy.decide(
                false, 0, 1_000, 0, false, 1_250, FIXED);
        assertTrue(at400.write());
        // adaptive=false 仍保留原有 idle 心跳行为
        TrackSamplingPolicy.Decision idle = TrackSamplingPolicy.decide(
                true, 1_000, 1_000, 0, false, 61_500, FIXED);
        assertTrue(idle.write());
        assertTrue(idle.heartbeat());
    }

    @Test
    void intervalMatchesTargetHz() {
        assertEquals(333L, TrackSamplingPolicy.intervalMs(3));
        assertEquals(200L, TrackSamplingPolicy.intervalMs(5));
        assertEquals(50L, TrackSamplingPolicy.intervalMs(20));
    }
}
