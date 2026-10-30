package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.track.TrackLayout;
import com.shenqianye.samsara.track.TrackStore;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 回看专用只读读取器（设计 5.7.2）。
 * <p>
 * <b>只读承诺</b>：只用 {@link Files#readAllBytes(Path)} / {@link Files#list(Path)}
 * 等读取接口，不出现 CREATE/APPEND/DELETE/MOVE。截断点（cutoffs）只读。
 * <p>
 * 尾部不完整（录制端崩溃/正在追加）解析到最后一个<b>完整</b>样本并 WARN；
 * magic/version 损坏则抛 {@link IOException}（F-89 拒绝开始并说明原因）。
 * LRU 键 = 路径 + size + mtime，文件变动即失效。
 */
public final class ViewTrackReader {
    private static final int MAX_CACHE_FILES = 8;
    private static final Map<CacheKey, ParsedFile> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, ParsedFile> eldest) {
                    return size() > MAX_CACHE_FILES;
                }
            };

    private ViewTrackReader() {
    }

    /** 单文件解析结果 */
    public record ParsedFile(List<TrackStore.Segment> segments, boolean truncated, long size, long mtime) {
        public int sampleCount() {
            int n = 0;
            for (TrackStore.Segment s : segments) {
                n += s.samples.size();
            }
            return n;
        }

        public long firstMs() {
            long min = Long.MAX_VALUE;
            for (TrackStore.Segment s : segments) {
                if (!s.samples.isEmpty()) {
                    min = Math.min(min, s.samples.get(0).timeMs());
                }
            }
            return min;
        }

        public long lastMs() {
            long max = Long.MIN_VALUE;
            for (TrackStore.Segment s : segments) {
                if (!s.samples.isEmpty()) {
                    max = Math.max(max, s.samples.get(s.samples.size() - 1).timeMs());
                }
            }
            return max;
        }
    }

    /** 一次窗口加载结果 */
    public record LoadResult(List<ViewCursor.Segment> segments, List<Path> files,
                             boolean truncated, boolean cutoffApplied, int totalSamples) {
    }

    /** 可回看时段（/track list） */
    public record Available(int cycleId, LocalDate day, Path file, long firstMs, long lastMs,
                            int samples, boolean truncated, boolean cutoffApplied, String error) {
    }

    private record CacheKey(Path path, long size, long mtime) {
    }

    // ---- 单文件 ----

    public static ParsedFile read(Path file) throws IOException {
        long size = Files.size(file);
        FileTime modified = Files.getLastModifiedTime(file);
        CacheKey key = new CacheKey(file.toAbsolutePath().normalize(), size, modified.toMillis());
        synchronized (CACHE) {
            ParsedFile cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        ParsedFile parsed = parse(Files.readAllBytes(file), file);
        synchronized (CACHE) {
            CACHE.put(key, parsed);
        }
        return parsed;
    }

    /** 测试/命令可主动清缓存（不影响磁盘） */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static ParsedFile parse(byte[] b, Path file) throws IOException {
        if (b.length < TrackStore.MAGIC.length + 1) {
            return new ParsedFile(List.of(), true, b.length, 0);
        }
        for (int i = 0; i < TrackStore.MAGIC.length; i++) {
            if (b[i] != TrackStore.MAGIC[i]) {
                throw new IOException("不是 track 文件: " + file.getFileName());
            }
        }
        int version = b[TrackStore.MAGIC.length] & 0xFF;
        if (version != TrackStore.VERSION) {
            throw new IOException("track 版本不支持: " + version + "（" + file.getFileName() + "）");
        }

        List<TrackStore.Segment> out = new ArrayList<>();
        int[] pos = {TrackStore.MAGIC.length + 1};
        TrackStore.Segment seg = null;
        int curX = 0;
        int curY = 0;
        int curZ = 0;
        long time = 0;
        boolean truncated = false;

        while (pos[0] < b.length) {
            int tag = b[pos[0]] & 0xFF;
            if (tag == 0x01) {
                pos[0]++;
                try {
                    seg = readSegmentHeader(b, pos, file);
                } catch (EOFException e) {
                    truncated = true;
                    break;
                }
                out.add(seg);
                curX = curY = curZ = 0;
                time = seg.startMs;
                continue;
            }
            if (seg == null) {
                throw new IOException("track 文件缺少段头: " + file.getFileName());
            }
            int mark = pos[0];
            try {
                long dt = TrackStore.readVarInt(b, pos) & 0xFFFFFFFFL;
                curX += TrackStore.unzigzag(TrackStore.readVarInt(b, pos));
                curY += TrackStore.unzigzag(TrackStore.readVarInt(b, pos));
                curZ += TrackStore.unzigzag(TrackStore.readVarInt(b, pos));
                if (pos[0] + 3 > b.length) {
                    throw new EOFException("track 样本截断");
                }
                float yaw = TrackStore.dequantizeAngle(b[pos[0]++]);
                float pitch = TrackStore.dequantizeAngle(b[pos[0]++]);
                int flags = b[pos[0]++] & 0x7F;
                time += dt;
                seg.samples.add(new TrackStore.Sample(time,
                        curX / TrackStore.FIXED, curY / TrackStore.FIXED, curZ / TrackStore.FIXED,
                        yaw, pitch, flags));
            } catch (EOFException e) {
                pos[0] = mark;
                truncated = true;
                break;
            }
        }
        if (truncated) {
            Samsara.LOGGER.warn("[回看] 轨迹文件尾部不完整，已截断到最后一个完整样本: {}", file);
        }
        return new ParsedFile(List.copyOf(out), truncated, b.length, 0);
    }

    private static TrackStore.Segment readSegmentHeader(byte[] b, int[] pos, Path file) throws IOException {
        if (pos[0] + 20 > b.length) {
            throw new EOFException("track 段头截断");
        }
        long msb = readLong(b, pos);
        long lsb = readLong(b, pos);
        if (pos[0] + 2 > b.length) {
            throw new EOFException("track 段头截断");
        }
        int dimLen = ((b[pos[0]] & 0xFF) << 8) | (b[pos[0] + 1] & 0xFF);
        pos[0] += 2;
        if (pos[0] + dimLen + 10 > b.length) {
            throw new EOFException("track 段头截断");
        }
        String dim = new String(b, pos[0], dimLen, StandardCharsets.UTF_8);
        pos[0] += dimLen;
        long start = readLong(b, pos);
        int hzX100 = ((b[pos[0]] & 0xFF) << 8) | (b[pos[0] + 1] & 0xFF);
        pos[0] += 2;
        return new TrackStore.Segment(new UUID(msb, lsb), dim, start, hzX100 / 100.0);
    }

    private static long readLong(byte[] b, int[] pos) throws IOException {
        if (pos[0] + 8 > b.length) {
            throw new EOFException("track long 截断");
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos[0]++] & 0xFF);
        }
        return v;
    }

    // ---- 窗口加载 ----

    /**
     * 加载 [from, to) 内某玩家某周目的全部轨迹文件（跨日期自动枚举）。
     *
     * @param cutoff 捕捉截断点；null = 无截断。命中时间轴 <= cutoff 的样本保留
     */
    public static LoadResult load(Path worldRoot, int cycleId, UUID uuid,
                                  long from, long to, Long cutoff) throws IOException {
        List<ViewCursor.Segment> segments = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        boolean truncated = false;
        boolean cutoffApplied = false;
        int total = 0;

        LocalDate day = ViewTime.dayOf(from);
        LocalDate lastDay = ViewTime.dayOf(Math.max(from, to - 1));
        for (LocalDate d = day; !d.isAfter(lastDay); d = d.plusDays(1)) {
            Path file = TrackLayout.file(worldRoot, cycleId, d, uuid);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            ParsedFile parsed = read(file);
            files.add(file);
            truncated |= parsed.truncated();
            for (TrackStore.Segment seg : parsed.segments()) {
                if (seg.samples.isEmpty()) {
                    continue;
                }
                List<TrackStore.Sample> samples = seg.samples;
                if (cutoff != null) {
                    List<TrackStore.Sample> cut = TrackStore.truncate(samples, cutoff);
                    if (cut.size() != samples.size()) {
                        cutoffApplied = true;
                    }
                    samples = cut;
                }
                if (samples.isEmpty()) {
                    continue;
                }
                segments.add(new ViewCursor.Segment(seg.dimension, samples));
                total += samples.size();
            }
        }
        if (files.isEmpty()) {
            throw new IOException("该时间窗口没有轨迹文件");
        }
        return new LoadResult(List.copyOf(segments), List.copyOf(files), truncated, cutoffApplied, total);
    }

    /**
     * 多账号入口版本：按同一身份组聚合 [from,to) 的轨迹。
     * cutoff 按文件对应 UUID 分别取，避免把某个 alias 的捕捉截断套到另一个 alias。
     */
    public static LoadResult load(Path worldRoot, int cycleId, java.util.Collection<UUID> uuids,
                                  long from, long to,
                                  java.util.function.BiFunction<UUID, Integer, Long> cutoffFor)
            throws IOException {
        List<ViewCursor.Segment> segments = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        boolean truncated = false;
        boolean cutoffApplied = false;
        int total = 0;
        LocalDate day = ViewTime.dayOf(from);
        LocalDate lastDay = ViewTime.dayOf(Math.max(from, to - 1));
        if (uuids != null) {
            for (UUID uuid : uuids) {
                Long cutoff = cutoffFor == null ? null : cutoffFor.apply(uuid, cycleId);
                for (LocalDate d = day; !d.isAfter(lastDay); d = d.plusDays(1)) {
                    Path file = TrackLayout.file(worldRoot, cycleId, d, uuid);
                    if (!Files.isRegularFile(file) || files.contains(file)) {
                        continue;
                    }
                    ParsedFile parsed = read(file);
                    files.add(file);
                    truncated |= parsed.truncated();
                    for (TrackStore.Segment seg : parsed.segments()) {
                        if (seg.samples.isEmpty()) {
                            continue;
                        }
                        List<TrackStore.Sample> samples = seg.samples;
                        if (cutoff != null) {
                            List<TrackStore.Sample> cut = TrackStore.truncate(samples, cutoff);
                            if (cut.size() != samples.size()) {
                                cutoffApplied = true;
                            }
                            samples = cut;
                        }
                        if (samples.isEmpty()) {
                            continue;
                        }
                        segments.add(new ViewCursor.Segment(seg.dimension, samples));
                        total += samples.size();
                    }
                }
            }
        }
        if (files.isEmpty()) {
            throw new IOException("该时间窗口没有轨迹文件");
        }
        return new LoadResult(List.copyOf(segments), List.copyOf(files), truncated, cutoffApplied, total);
    }

    // ---- 可回看时段 ----

    /** 扫描同一身份组多个账号入口的轨迹文件（按 data UUID 聚合）。 */
    public static List<Available> available(Path worldRoot, java.util.Collection<UUID> uuids,
                                            java.util.function.BiFunction<UUID, Integer, Long> cutoffFor) {
        Map<Path, Available> merged = new LinkedHashMap<>();
        if (uuids != null) {
            for (UUID uuid : uuids) {
                if (uuid == null) {
                    continue;
                }
                List<Available> one = available(worldRoot, uuid,
                        cycle -> cutoffFor == null ? null : cutoffFor.apply(uuid, cycle));
                for (Available available : one) {
                    merged.putIfAbsent(available.file(), available);
                }
            }
        }
        List<Available> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingLong(Available::firstMs).reversed());
        return out;
    }

    /** 扫描某玩家全部周目/日期文件（只读），用于 {@code /track list}。 */
    public static List<Available> available(Path worldRoot, UUID uuid,
                                            java.util.function.IntFunction<Long> cutoffForCycle) {
        List<Available> out = new ArrayList<>();
        if (worldRoot == null || !Files.isDirectory(worldRoot)) {
            return out;
        }
        List<Integer> cycles = new ArrayList<>();
        try (Stream<Path> list = Files.list(worldRoot)) {
            list.filter(Files::isDirectory).forEach(p -> {
                int id = TrackLayout.cycleIdOf(p.getFileName().toString());
                if (id >= 0) {
                    cycles.add(id);
                }
            });
        } catch (IOException e) {
            return out;
        }
        cycles.sort(Comparator.reverseOrder());
        for (int cycle : cycles) {
            Long cutoff = cutoffForCycle == null ? null : cutoffForCycle.apply(cycle);
            Path cycleDir = TrackLayout.cycleDir(worldRoot, cycle);
            List<LocalDate> days = new ArrayList<>();
            try (Stream<Path> list = Files.list(cycleDir)) {
                list.filter(Files::isDirectory).forEach(p -> {
                    String name = p.getFileName().toString();
                    if (TrackLayout.isDateDir(name)) {
                        try {
                            days.add(LocalDate.parse(name));
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (IOException ignored) {
                continue;
            }
            days.sort(Comparator.reverseOrder());
            for (LocalDate day : days) {
                Path file = TrackLayout.file(worldRoot, cycle, day, uuid);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    ParsedFile parsed = read(file);
                    long first = parsed.firstMs();
                    long last = parsed.lastMs();
                    boolean cut = false;
                    if (cutoff != null && first <= cutoff && cutoff < last) {
                        cut = true;
                        last = cutoff;
                    }
                    out.add(new Available(cycle, day, file, first, last,
                            parsed.sampleCount(), parsed.truncated(), cut, null));
                } catch (IOException e) {
                    out.add(new Available(cycle, day, file, 0, 0, 0, false, false,
                            e.getMessage()));
                }
            }
        }
        return out;
    }
}
