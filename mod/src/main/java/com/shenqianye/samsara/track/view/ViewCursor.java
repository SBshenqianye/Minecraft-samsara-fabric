package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.track.TrackStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 回看光标（设计 5.7.1 / 5.7.2）：只读推进。
 * <p>
 * 把多个文件/多个记录段按首样本时间拼接；窗口裁剪后提供 O(log n) 二分定位与
 * O(1) 线性插值。段与段之间若存在时间空隙，按设计 5.7.9 从下一段起点继续，
 * 不会在两个不连续的记录之间"补出"移动轨迹。
 * <p>
 * 纯 Java（不依赖 Minecraft），可直接单测。
 */
public final class ViewCursor {
    /** 一个记录段：同一维度、时间升序的样本 */
    public record Segment(String dimension, List<TrackStore.Sample> samples) {
        public Segment {
            samples = List.copyOf(samples);
        }

        public long firstMs() {
            return samples.get(0).timeMs();
        }

        public long lastMs() {
            return samples.get(samples.size() - 1).timeMs();
        }
    }

    /** 插值结果（带维度） */
    public record Point(long timeMs, double x, double y, double z,
                        float yaw, float pitch, int flags, String dimension) {
    }

    private final List<Segment> segments;
    private final long requestedFrom;
    private final long windowTo;
    private final long startMs;
    private final long endMs;
    private final boolean truncated;
    private final boolean cutoffApplied;

    private ViewCursor(List<Segment> segments, long requestedFrom, long windowTo,
                       long startMs, long endMs, boolean truncated, boolean cutoffApplied) {
        this.segments = segments;
        this.requestedFrom = requestedFrom;
        this.windowTo = windowTo;
        this.startMs = startMs;
        this.endMs = endMs;
        this.truncated = truncated;
        this.cutoffApplied = cutoffApplied;
    }

    public static ViewCursor build(List<Segment> raw, long from, long to,
                                   boolean truncated, boolean cutoffApplied) {
        List<Segment> sorted = new ArrayList<>();
        for (Segment s : raw) {
            if (s != null && s.dimension() != null && !s.samples().isEmpty()) {
                sorted.add(s);
            }
        }
        sorted.sort(Comparator.comparingLong(Segment::firstMs));

        List<Segment> cropped = new ArrayList<>();
        for (Segment s : sorted) {
            List<TrackStore.Sample> kept = crop(s.samples(), from, to);
            if (!kept.isEmpty()) {
                cropped.add(new Segment(s.dimension(), kept));
            }
        }
        if (cropped.isEmpty()) {
            throw new IllegalArgumentException("所选时间窗口内没有可用轨迹样本");
        }

        // 起始时刻：窗口起点；若落在两段之间，从下一段起点开始（5.7.9）
        long start = from;
        Segment first = cropped.get(0);
        if (start < first.firstMs()) {
            start = first.firstMs();
        } else {
            for (Segment s : cropped) {
                if (s.lastMs() >= start) {
                    if (s.firstMs() > start) {
                        start = s.firstMs();
                    }
                    break;
                }
            }
        }
        long last = cropped.get(cropped.size() - 1).lastMs();
        long end = Math.min(last, to);
        if (end < start) {
            end = last;
        }
        return new ViewCursor(List.copyOf(cropped), from, to, start, end,
                truncated, cutoffApplied);
    }

    /** 裁剪：保留 [from,to] 内的样本，并保留 from 前最后一个样本作为插值锚点。 */
    public static List<TrackStore.Sample> crop(List<TrackStore.Sample> samples, long from, long to) {
        if (samples.isEmpty()) {
            return List.of();
        }
        int anchor = -1;
        int lo = 0;
        int hi = samples.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = samples.get(mid).timeMs();
            if (t <= from) {
                anchor = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int first = anchor >= 0 ? anchor : 0;
        List<TrackStore.Sample> out = new ArrayList<>();
        for (int i = first; i < samples.size(); i++) {
            long t = samples.get(i).timeMs();
            if (t < from && i != anchor) {
                continue;
            }
            if (t > to) {
                break;
            }
            out.add(samples.get(i));
        }
        // 窗口完全落在该段之后：只保留最后一个样本会让"下一段起点"判断失真，直接丢弃
        if (!out.isEmpty() && out.get(out.size() - 1).timeMs() < from) {
            return List.of();
        }
        return out;
    }

    public List<Segment> segments() {
        return segments;
    }

    public int sampleCount() {
        int n = 0;
        for (Segment s : segments) {
            n += s.samples().size();
        }
        return n;
    }

    public int segmentCount() {
        return segments.size();
    }

    public long requestedFrom() {
        return requestedFrom;
    }

    public long windowTo() {
        return windowTo;
    }

    public long startMs() {
        return startMs;
    }

    public long endMs() {
        return endMs;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean cutoffApplied() {
        return cutoffApplied;
    }

    public long remainingMs(long trackTime) {
        return Math.max(0, endMs - trackTime);
    }

    public float progress(long trackTime) {
        long span = endMs - startMs;
        if (span <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (trackTime - startMs) / (float) span));
    }

    /** 定位并插值；t 早于首样本或晚于末样本时钳到边界。 */
    public Point pointAt(long t) {
        Segment seg = segmentFor(t);
        if (seg == null) {
            Segment last = segments.get(segments.size() - 1);
            return pointOf(last.samples().get(last.samples().size() - 1), last.dimension());
        }
        List<TrackStore.Sample> points = seg.samples();
        if (t <= points.get(0).timeMs()) {
            return pointOf(points.get(0), seg.dimension());
        }
        int idx = floorIndex(points, t);
        TrackStore.Sample a = points.get(idx);
        TrackStore.Sample b = points.get(Math.min(idx + 1, points.size() - 1));
        if (b.timeMs() <= a.timeMs()) {
            return pointOf(a, seg.dimension());
        }
        double f = (t - a.timeMs()) / (double) (b.timeMs() - a.timeMs());
        f = Math.max(0, Math.min(1, f));
        return new Point(t,
                a.x() + (b.x() - a.x()) * f,
                a.y() + (b.y() - a.y()) * f,
                a.z() + (b.z() - a.z()) * f,
                lerpAngle(a.yaw(), b.yaw(), f),
                (float) (a.pitch() + (b.pitch() - a.pitch()) * f),
                f < 0.5 ? a.flags() : b.flags(),
                seg.dimension());
    }

    /** 第一个 lastMs >= t 的段（t 在空隙时自然落到下一段，实现"从下一段起点开始"） */
    private Segment segmentFor(long t) {
        int lo = 0;
        int hi = segments.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (segments.get(mid).lastMs() >= t) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans < 0 ? null : segments.get(ans);
    }

    /** 最后一个 timeMs <= t 的下标；t 早于首样本时返回 0。 */
    public static int floorIndex(List<TrackStore.Sample> samples, long t) {
        int lo = 0;
        int hi = samples.size() - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).timeMs() <= t) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static Point pointOf(TrackStore.Sample s, String dimension) {
        return new Point(s.timeMs(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.flags(), dimension);
    }

    private static float lerpAngle(float a, float b, double f) {
        float delta = ((b - a + 540) % 360) - 180;
        return a + (float) (delta * f);
    }
}
