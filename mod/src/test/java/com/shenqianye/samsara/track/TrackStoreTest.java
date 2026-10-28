package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 轨迹二进制格式/几何简化（纯文件系统，不依赖 MC 运行时） */
class TrackStoreTest {
    @TempDir
    Path tmp;

    @Test
    void roundTripSegmentsAndSamples() throws Exception {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TrackStore.Segment seg = new TrackStore.Segment(uuid, "minecraft:overworld", 1_000_000L, 5.0);
        seg.samples.add(new TrackStore.Sample(1_000_000L, 0.5, 64.0, -2.25, 90f, -10f,
                TrackStore.FLAG_SPRINT));
        seg.samples.add(new TrackStore.Sample(1_000_200L, 3.25, 64.5, 1.0, 180f, 20f, 0));
        Path file = tmp.resolve("2026-10-28").resolve(uuid + ".bin");

        TrackStore.write(file, List.of(seg));
        List<TrackStore.Segment> back = TrackStore.read(file);

        assertEquals(1, back.size());
        TrackStore.Segment s = back.get(0);
        assertEquals(uuid, s.uuid);
        assertEquals("minecraft:overworld", s.dimension);
        assertEquals(1_000_000L, s.startMs);
        assertEquals(5.0, s.hz, 0.001);
        assertEquals(2, s.samples.size());
        assertEquals(0.5, s.samples.get(0).x(), 1.0 / 16);
        assertEquals(64.0, s.samples.get(0).y(), 1.0 / 16);
        assertEquals(-2.25, s.samples.get(0).z(), 1.0 / 16);
        assertEquals(3.25, s.samples.get(1).x(), 1.0 / 16);
        assertEquals(1.0, s.samples.get(1).z(), 1.0 / 16);
        assertEquals(1_000_200L, s.samples.get(1).timeMs());
        assertEquals(TrackStore.FLAG_SPRINT, s.samples.get(0).flags());
        float yawDiff = Math.abs(((s.samples.get(1).yaw() - 180f) % 360f + 540f) % 360f - 180f);
        assertEquals(0f, yawDiff, TrackStore.DEG_PER_UNIT);
    }

    @Test
    void varintAndAngleRoundTrip() throws Exception {
        int[] values = {0, 1, -1, 127, 128, -128, 100000, -100000};
        for (int v : values) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            TrackStore.writeVarInt(out, TrackStore.zigzag(v));
            byte[] b = out.toByteArray();
            int[] pos = {0};
            assertEquals(v, TrackStore.unzigzag(TrackStore.readVarInt(b, pos)));
        }
        assertEquals(90f, TrackStore.dequantizeAngle(TrackStore.quantizeAngle(90f)),
                TrackStore.DEG_PER_UNIT);
    }

    @Test
    void decimateSimplifiesStraightPathButKeepsEndpoints() throws Exception {
        TrackStore.Segment seg = new TrackStore.Segment(
                UUID.randomUUID(), "minecraft:overworld", 0L, 5.0);
        for (int i = 0; i < 1000; i++) {
            seg.samples.add(new TrackStore.Sample(i * 200L, i * 0.2, 64, 0, 0, 0, 0));
        }
        Path file = tmp.resolve("2026-10-01").resolve("x.bin");
        TrackStore.write(file, List.of(seg));

        assertTrue(TrackStore.decimate(file, 1.25, 100));
        TrackStore.Segment back = TrackStore.read(file).get(0);
        assertTrue(back.samples.size() <= 100);
        assertEquals(0.0, back.samples.get(0).x(), 0.001);
        assertEquals(999 * 0.2, back.samples.get(back.samples.size() - 1).x(), 0.1);
        assertTrue(back.hz <= 1.25);
    }

    @Test
    void decimateKeepsSkeletonFloor() throws Exception {
        TrackStore.Segment seg = new TrackStore.Segment(
                UUID.randomUUID(), "minecraft:overworld", 0L, 5.0);
        for (int i = 0; i < 80; i++) {
            seg.samples.add(new TrackStore.Sample(i * 200L, i, 64, i % 7, 0, 0, 0));
        }
        Path file = tmp.resolve("2026-10-02").resolve("y.bin");
        TrackStore.write(file, List.of(seg));
        assertEquals(false, TrackStore.decimate(file, 0.05, 2000));
        assertEquals(80, TrackStore.read(file).get(0).samples.size());
    }

    @Test
    void simplifyKeepsTurns() {
        List<TrackStore.Sample> pts = new ArrayList<>();
        pts.add(new TrackStore.Sample(0, 0, 64, 0, 0, 0, 0));
        pts.add(new TrackStore.Sample(1000, 10, 64, 0, 0, 0, 0));
        pts.add(new TrackStore.Sample(2000, 10, 64, 10, 0, 0, 0));
        List<TrackStore.Sample> out = TrackStore.simplify(pts, 0.5);
        assertEquals(3, out.size());
    }
}
