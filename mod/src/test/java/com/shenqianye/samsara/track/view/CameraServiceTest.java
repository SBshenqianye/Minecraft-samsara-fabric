package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraServiceTest {
    @Test
    void defaultsToBothTrackAndDiff() {
        CameraService.StartRequest request = CameraService.parse(
                "Alice 2026-11-08T10:00 --speed 4 --duration 5m");
        assertEquals(List.of("Alice"), request.targets());
        assertFalse(request.all());
        assertEquals("2026-11-08T10:00", request.start());
        assertTrue(request.track());
        assertTrue(request.diff());
        assertEquals(4.0, request.speed(), 0.0001);
        assertTrue(request.trackOptions().contains("--duration 5m"));
        assertTrue(request.diffTo() != null);
    }

    @Test
    void canDisableOneSide() {
        CameraService.StartRequest diffOnly = CameraService.parse(
                "all 2026-11-08T10:00 --track=off");
        assertTrue(diffOnly.all());
        assertFalse(diffOnly.track());
        assertTrue(diffOnly.diff());

        CameraService.StartRequest trackOnly = CameraService.parse(
                "2026-11-08T10:00 --diff off --diff-radius 4 --diff-dim minecraft:overworld");
        assertTrue(trackOnly.track());
        assertFalse(trackOnly.diff());
        assertEquals(4, trackOnly.diffRadius());
        assertEquals("minecraft:overworld", trackOnly.diffDimension());
    }

    @Test
    void rejectsMissingStartOrBadRadius() {
        assertThrows(IllegalArgumentException.class, () -> CameraService.parse("Alice"));
        assertThrows(IllegalArgumentException.class,
                () -> CameraService.parse("Alice 2026-11-08T10:00 --diff-radius 99"));
    }
}
