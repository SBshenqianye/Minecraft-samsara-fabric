package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回看参数：自瞄默认值/覆盖/校验，以及回看禁止 --interact。 */
class ViewOptionsTest {
    private static final long START = 1_000_000L;
    private static final long END = 2_000_000L;

    @Test
    void defaultAimComesFromConfig() {
        ViewOptions o = ViewOptions.parse("", START, END);
        assertNull(o.aim());
        assertEquals(6.0, o.aimDeadzone(), 1e-9);
        assertEquals(24.0, o.aimEdge(), 1e-9);
        assertEquals(90.0, o.aimSpeed(), 1e-9);
        assertTrue(o.aimPitch());
    }

    @Test
    void explicitAimOverrides() {
        ViewOptions o = ViewOptions.parse(
                "--aim=on --aim-deadzone 10 --aim-edge 30 --aim-speed 120 --aim-pitch off",
                START, END);
        assertEquals(Boolean.TRUE, o.aim());
        assertEquals(10.0, o.aimDeadzone(), 1e-9);
        assertEquals(30.0, o.aimEdge(), 1e-9);
        assertEquals(120.0, o.aimSpeed(), 1e-9);
        assertFalse(o.aimPitch());
    }

    @Test
    void bareAimAndOnOffAreAccepted() {
        assertEquals(Boolean.TRUE, ViewOptions.parse("--aim", START, END).aim());
        assertEquals(Boolean.FALSE, ViewOptions.parse("--aim off", START, END).aim());
    }

    @Test
    void interactIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ViewOptions.parse("--interact", START, END));
    }

    @Test
    void invalidAimRangesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ViewOptions.parse("--aim-deadzone 50", START, END));
        assertThrows(IllegalArgumentException.class,
                () -> ViewOptions.parse("--aim-deadzone 20 --aim-edge 10", START, END));
        assertThrows(IllegalArgumentException.class,
                () -> ViewOptions.parse("--aim-speed 5", START, END));
    }

    @Test
    void followStillAcceptsOnOffWithoutSwallowingNextOption() {
        ViewOptions o = ViewOptions.parse("--follow off --aim on", START, END);
        assertFalse(o.follow());
        assertEquals(Boolean.TRUE, o.aim());
    }
}
