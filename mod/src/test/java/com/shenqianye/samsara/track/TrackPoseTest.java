package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** flags 位解析与姿态映射（纯 Java，不启动 MC） */
class TrackPoseTest {
    @Test
    void zeroFlagsIsStandingWithNoVisibleState() {
        TrackPose.State s = TrackPose.of(0);
        assertFalse(s.sneak());
        assertFalse(s.sprint());
        assertFalse(s.flying());
        assertFalse(s.swimming());
        assertFalse(s.riding());
        assertFalse(s.hurt());
        assertFalse(s.dead());
        assertEquals(TrackPose.Posture.STANDING, s.posture());
        assertTrue(s.standing());
    }

    @Test
    void eachFlagBitMapsToItsOwnBoolean() {
        TrackPose.State sneak = TrackPose.of(TrackStore.FLAG_SNEAK);
        assertTrue(sneak.sneak());
        assertEquals(TrackPose.Posture.SNEAK, sneak.posture());

        TrackPose.State sprint = TrackPose.of(TrackStore.FLAG_SPRINT);
        assertTrue(sprint.sprint());
        assertEquals(TrackPose.Posture.STANDING, sprint.posture());

        TrackPose.State flying = TrackPose.of(TrackStore.FLAG_FLYING);
        assertTrue(flying.flying());
        assertEquals(TrackPose.Posture.FLYING, flying.posture());

        TrackPose.State swimming = TrackPose.of(TrackStore.FLAG_SWIMMING);
        assertTrue(swimming.swimming());
        assertEquals(TrackPose.Posture.SWIMMING, swimming.posture());

        TrackPose.State riding = TrackPose.of(TrackStore.FLAG_RIDING);
        assertTrue(riding.riding());
        assertEquals(TrackPose.Posture.RIDING, riding.posture());

        TrackPose.State hurt = TrackPose.of(TrackStore.FLAG_HURT);
        assertTrue(hurt.hurt());
        assertEquals(TrackPose.Posture.STANDING, hurt.posture());

        TrackPose.State dead = TrackPose.of(TrackStore.FLAG_DEAD);
        assertTrue(dead.dead());
        assertEquals(TrackPose.Posture.DEAD, dead.posture());
    }

    @Test
    void allSevenBitsSetMapsToAllStates() {
        int all = TrackStore.FLAG_SNEAK | TrackStore.FLAG_SPRINT | TrackStore.FLAG_FLYING
                | TrackStore.FLAG_SWIMMING | TrackStore.FLAG_RIDING
                | TrackStore.FLAG_HURT | TrackStore.FLAG_DEAD;
        TrackPose.State s = TrackPose.of(all);
        assertTrue(s.sneak());
        assertTrue(s.sprint());
        assertTrue(s.flying());
        assertTrue(s.swimming());
        assertTrue(s.riding());
        assertTrue(s.hurt());
        assertTrue(s.dead());
        assertEquals(TrackPose.Posture.DEAD, s.posture());
    }

    @Test
    void sprintAndHurtAreIndependentOfPosture() {
        int flags = TrackStore.FLAG_SNEAK | TrackStore.FLAG_SPRINT | TrackStore.FLAG_HURT;
        TrackPose.State s = TrackPose.of(flags);
        assertTrue(s.sneak());
        assertTrue(s.sprint());
        assertTrue(s.hurt());
        assertEquals(TrackPose.Posture.SNEAK, s.posture());
    }

    @Test
    void posturePriorityFollowsDefinedOrder() {
        // 死亡 > 骑乘 > 飞行 > 游泳 > 潜行
        assertEquals(TrackPose.Posture.DEAD, TrackPose.postureOf(
                TrackStore.FLAG_DEAD | TrackStore.FLAG_RIDING | TrackStore.FLAG_FLYING
                        | TrackStore.FLAG_SWIMMING | TrackStore.FLAG_SNEAK));
        assertEquals(TrackPose.Posture.RIDING, TrackPose.postureOf(
                TrackStore.FLAG_RIDING | TrackStore.FLAG_FLYING
                        | TrackStore.FLAG_SWIMMING | TrackStore.FLAG_SNEAK));
        assertEquals(TrackPose.Posture.FLYING, TrackPose.postureOf(
                TrackStore.FLAG_FLYING | TrackStore.FLAG_SWIMMING | TrackStore.FLAG_SNEAK));
        assertEquals(TrackPose.Posture.SWIMMING, TrackPose.postureOf(
                TrackStore.FLAG_SWIMMING | TrackStore.FLAG_SNEAK));
        assertEquals(TrackPose.Posture.SNEAK, TrackPose.postureOf(TrackStore.FLAG_SNEAK));
    }

    @Test
    void creativeFlightIsNotRenderedAsElytra() {
        int flying = TrackStore.FLAG_FLYING;
        assertEquals(TrackPose.Posture.FLYING,
                TrackPose.postureOf(flying, TrackEvents.FLIGHT_ELYTRA));
        assertEquals(TrackPose.Posture.FLYING, TrackPose.postureOf(flying, -1));
        assertEquals(TrackPose.Posture.STANDING,
                TrackPose.postureOf(flying, TrackEvents.FLIGHT_CREATIVE));
        assertEquals(TrackPose.Posture.STANDING,
                TrackPose.postureOf(flying, TrackEvents.FLIGHT_NONE));
        assertEquals(TrackPose.Posture.SNEAK,
                TrackPose.postureOf(flying | TrackStore.FLAG_SNEAK, TrackEvents.FLIGHT_CREATIVE));
    }

    @Test
    void reservedHighBitsAreIgnored() {
        // v1 只写低 7 位；高位/负数脏数据按 0 处理，避免误判姿态
        TrackPose.State s = TrackPose.of(0x80 | TrackStore.FLAG_SPRINT);
        assertTrue(s.sprint());
        assertEquals(TrackPose.Posture.STANDING, s.posture());
        assertFalse(TrackPose.of(-1).standing());
        assertEquals(TrackPose.Posture.DEAD, TrackPose.of(-1).posture());
    }

}
