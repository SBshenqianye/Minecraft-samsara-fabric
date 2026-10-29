package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.track.TrackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回看光标：多段拼接、窗口裁剪、二分边界、尾部截断。 */
class ViewCursorTest {

    private static TrackStore.Sample sample(long t, double x, String ignored) {
        return new TrackStore.Sample(t, x, 64, 0, 0, 0, 0);
    }

    @Test
    void concatenatesSegmentsAndSnapsAcrossGaps() {
        ViewCursor.Segment a = new ViewCursor.Segment("minecraft:overworld", List.of(
                sample(1_000, 0, null), sample(2_000, 10, null)));
        ViewCursor.Segment b = new ViewCursor.Segment("minecraft:the_nether", List.of(
                sample(5_000, 100, null), sample(6_000, 110, null)));
        ViewCursor cursor = ViewCursor.build(List.of(b, a), 1_000, 6_000, false, false);

        assertEquals(2, cursor.segmentCount());
        assertEquals(4, cursor.sampleCount());
        assertEquals(1_000, cursor.startMs());
        assertEquals(6_000, cursor.endMs());

        ViewCursor.Point mid = cursor.pointAt(1_500);
        assertEquals(5.0, mid.x(), 1e-9);
        assertEquals("minecraft:overworld", mid.dimension());

        // 3_000 落在两段之间：按设计 5.7.9 从下一段起点开始，不补跨维度移动
        ViewCursor.Point gap = cursor.pointAt(3_000);
        assertEquals(5_000, gap.timeMs());
        assertEquals(100.0, gap.x(), 1e-9);
        assertEquals("minecraft:the_nether", gap.dimension());
    }

    @Test
    void cropsWindowAndKeepsInterpolationAnchor() {
        List<TrackStore.Sample> points = List.of(
                sample(0, 0, null), sample(100, 1, null), sample(200, 2, null),
                sample(300, 3, null), sample(400, 4, null), sample(500, 5, null),
                sample(600, 6, null), sample(700, 7, null));
        ViewCursor cursor = ViewCursor.build(List.of(
                new ViewCursor.Segment("minecraft:overworld", points)), 300, 600, false, false);

        assertEquals(300, cursor.startMs());
        assertEquals(600, cursor.endMs());
        assertEquals(4, cursor.sampleCount());
        ViewCursor.Point atStart = cursor.pointAt(300);
        assertEquals(3.0, atStart.x(), 1e-9);
        ViewCursor.Point atEnd = cursor.pointAt(600);
        assertEquals(6.0, atEnd.x(), 1e-9);

        // from 落在两个样本之间：保留前一个样本作为插值锚点
        List<TrackStore.Sample> cropped = ViewCursor.crop(points, 350, 600);
        assertEquals(300, cropped.get(0).timeMs());
        assertEquals(600, cropped.get(cropped.size() - 1).timeMs());
    }

    @Test
    void binarySearchHandlesBoundaries() {
        List<TrackStore.Sample> points = List.of(
                sample(100, 0, null), sample(200, 1, null), sample(300, 2, null));
        assertEquals(0, ViewCursor.floorIndex(points, 0));
        assertEquals(0, ViewCursor.floorIndex(points, 100));
        assertEquals(0, ViewCursor.floorIndex(points, 199));
        assertEquals(1, ViewCursor.floorIndex(points, 200));
        assertEquals(2, ViewCursor.floorIndex(points, 300));
        assertEquals(2, ViewCursor.floorIndex(points, 999));

        ViewCursor cursor = ViewCursor.build(List.of(
                new ViewCursor.Segment("minecraft:overworld", points)), 100, 300, false, false);
        assertEquals(0f, cursor.progress(100), 1e-6);
        assertEquals(1f, cursor.progress(300), 1e-6);
        assertEquals(0, cursor.remainingMs(300));
    }

    @Test
    void truncatedTailParsesToLastCompleteSample(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tail.bin");
        TrackStore.Segment seg = new TrackStore.Segment(
                java.util.UUID.randomUUID(), "minecraft:overworld", 1_000, 5.0);
        seg.samples.add(sample(1_000, 0, null));
        seg.samples.add(sample(1_200, 1, null));
        TrackStore.write(file, List.of(seg));

        byte[] full = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(full, full.length - 1));

        ViewTrackReader.ParsedFile parsed = ViewTrackReader.read(file);
        assertTrue(parsed.truncated());
        assertEquals(1, parsed.sampleCount());
        assertEquals(1_000, parsed.firstMs());
        assertEquals(1_000, parsed.lastMs());
    }

    @Test
    void emptyWindowIsRejected() {
        List<TrackStore.Sample> points = List.of(sample(1_000, 0, null));
        boolean thrown = false;
        try {
            ViewCursor.build(List.of(new ViewCursor.Segment("minecraft:overworld", points)),
                    9_000, 10_000, false, false);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown);
        assertFalse(points.isEmpty());
    }
}
