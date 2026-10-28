package com.shenqianye.samsara.track;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家轨迹的二进制存储（设计文档 §5.4）。
 * <p>
 * 文件：{@code config/samsara/track/<世界>/cycle<周目id>/<YYYY-MM-DD>/<uuid>.bin}，按本地时区按天轮转。
 * 世界层与周目层由 {@link TrackLayout}/{@link TrackRecorder} 决定，本类只负责单文件的二进制格式。
 * <pre>
 * 文件头：MAGIC "STRK"(4B) + version(1B)
 * 段：0x01 + uuid(16B) + dimLen(2B) + dim(UTF-8) + startMs(8B) + hzX100(2B)
 * 样本：dt varint + dx/dy/dz zigzag varint（1/16 格增量）+ yaw(1B) + pitch(1B) + flags(1B)
 * </pre>
 * {@code dt} 最小 10ms，因此样本首字节永远不会是 0x01（段标记），解析无歧义。
 * <p>
 * 采样率写在<b>段头</b>而不是每点：降采样时只需改段头。
 */
public final class TrackStore {
    public static final byte[] MAGIC = {'S', 'T', 'R', 'K'};
    public static final int VERSION = 1;
    /** 位置定点：1/16 格 */
    public static final double FIXED = 16.0;
    /** 角度量化：360° / 256 = 1.40625° */
    public static final float DEG_PER_UNIT = 360.0f / 256.0f;
    private static final int SEGMENT_TAG = 0x01;
    /** 几何简化误差（格/档；设计文档 §5.5：每档 ≤0.5，五档累计 ≤2.5） */
    public static final double SIMPLIFY_TOLERANCE = 0.5;

    public static final int FLAG_SNEAK = 1;
    public static final int FLAG_SPRINT = 1 << 1;
    public static final int FLAG_FLYING = 1 << 2;
    public static final int FLAG_SWIMMING = 1 << 3;
    public static final int FLAG_RIDING = 1 << 4;
    public static final int FLAG_HURT = 1 << 5;
    public static final int FLAG_DEAD = 1 << 6;

    private TrackStore() {
    }

    /** 一个采样点（时间是 epoch ms） */
    public record Sample(long timeMs, double x, double y, double z, float yaw, float pitch, int flags) {
        public int fixedX() {
            return (int) Math.round(x * FIXED);
        }

        public int fixedY() {
            return (int) Math.round(y * FIXED);
        }

        public int fixedZ() {
            return (int) Math.round(z * FIXED);
        }
    }

    /** 一次连续采样段（同一维度、同一采样率） */
    public static final class Segment {
        public UUID uuid;
        public String dimension;
        public long startMs;
        public double hz;
        public List<Sample> samples = new ArrayList<>();

        public Segment() {
        }

        public Segment(UUID uuid, String dimension, long startMs, double hz) {
            this.uuid = uuid;
            this.dimension = dimension;
            this.startMs = startMs;
            this.hz = hz;
        }

        public long endMs() {
            return samples.isEmpty() ? startMs : samples.get(samples.size() - 1).timeMs();
        }
    }

    // ---- 写 ----

    /** 段头字节（供追加写使用） */
    public static byte[] encodeSegmentHeader(Segment s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(SEGMENT_TAG);
            out.writeLong(s.uuid.getMostSignificantBits());
            out.writeLong(s.uuid.getLeastSignificantBits());
            byte[] dim = s.dimension.getBytes(StandardCharsets.UTF_8);
            out.writeShort(dim.length);
            out.write(dim);
            out.writeLong(s.startMs);
            out.writeShort((int) Math.round(s.hz * 100));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    /** 单个样本字节（prev 用于计算增量；首样本传 null 时按绝对定点写） */
    public static byte[] encodeSample(Sample prev, Sample cur) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
        long dt = prev == null ? 0 : cur.timeMs() - prev.timeMs();
        writeVarInt(bos, prev == null ? 0 : (int) Math.max(10, Math.min(Integer.MAX_VALUE, dt)));
        int px = prev == null ? 0 : prev.fixedX();
        int py = prev == null ? 0 : prev.fixedY();
        int pz = prev == null ? 0 : prev.fixedZ();
        writeVarInt(bos, zigzag(cur.fixedX() - px));
        writeVarInt(bos, zigzag(cur.fixedY() - py));
        writeVarInt(bos, zigzag(cur.fixedZ() - pz));
        bos.write(quantizeAngle(cur.yaw()));
        bos.write(quantizeAngle(cur.pitch()));
        bos.write(cur.flags() & 0x7F);
        return bos.toByteArray();
    }

    public static byte[] header() {
        byte[] out = new byte[MAGIC.length + 1];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) VERSION;
        return out;
    }

    /** 整文件重写（降采样后用；先写临时文件再原子替换） */
    public static void write(Path file, List<Segment> segments) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(header());
        for (Segment s : segments) {
            bos.write(encodeSegmentHeader(s));
            Sample prev = null;
            for (Sample sample : s.samples) {
                bos.write(encodeSample(prev, sample));
                prev = sample;
            }
        }
        Files.write(tmp, bos.toByteArray());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- 读 ----

    public static List<Segment> read(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        if (b.length < MAGIC.length + 1) {
            return List.of();
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (b[i] != MAGIC[i]) {
                throw new IOException("不是 track 文件: " + file);
            }
        }
        if ((b[MAGIC.length] & 0xFF) != VERSION) {
            throw new IOException("track 版本不支持: " + (b[MAGIC.length] & 0xFF));
        }
        int[] pos = {MAGIC.length + 1};
        List<Segment> out = new ArrayList<>();
        Segment seg = null;
        Sample prev = null;
        int curX = 0;
        int curY = 0;
        int curZ = 0;
        long time = 0;
        while (pos[0] < b.length) {
            int tag = b[pos[0]] & 0xFF;
            if (tag == SEGMENT_TAG) {
                pos[0]++;
                seg = readSegmentHeader(b, pos);
                out.add(seg);
                prev = null;
                curX = curY = curZ = 0;
                time = seg.startMs;
                continue;
            }
            if (seg == null) {
                throw new IOException("track 文件缺少段头: " + file);
            }
            long dt = readVarInt(b, pos) & 0xFFFFFFFFL;
            curX += unzigzag(readVarInt(b, pos));
            curY += unzigzag(readVarInt(b, pos));
            curZ += unzigzag(readVarInt(b, pos));
            float yaw = dequantizeAngle(readByte(b, pos));
            float pitch = dequantizeAngle(readByte(b, pos));
            int flags = readByte(b, pos) & 0x7F;
            time += dt;
            Sample s = new Sample(time, curX / FIXED, curY / FIXED, curZ / FIXED, yaw, pitch, flags);
            seg.samples.add(s);
            prev = s;
        }
        return out;
    }

    private static Segment readSegmentHeader(byte[] b, int[] pos) throws IOException {
        if (pos[0] + 20 > b.length) {
            throw new EOFException("track 段头截断");
        }
        long msb = readLong(b, pos);
        long lsb = readLong(b, pos);
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
        return new Segment(new UUID(msb, lsb), dim, start, hzX100 / 100.0);
    }

    // ---- 捕捉截断（capture cutoff）----

    /**
     * 截取 {@code timeMs <= cutoffMs} 的前缀样本（捕捉后不再回放截断点之后的轨迹）。
     * 样本按时间升序，命中第一个更晚的样本即可停止。
     */
    public static List<Sample> truncate(List<Sample> samples, long cutoffMs) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) {
            if (s.timeMs() > cutoffMs) {
                break;
            }
            out.add(s);
        }
        return out;
    }

    /** 最后一个 timeMs <= t 的样本下标（二分；t 早于首样本时返回 0） */
    public static int floorIndex(List<Sample> samples, long t) {
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

    // ---- 降采样（几何简化，非"隔一个丢一个"）----

    /**
     * 对整文件做一档降采样：每段做 RDP 几何简化（误差 tolerance 格），并把段头采样率改成
     * {@code targetHz}。样本总数低于骨架下限时不动。返回是否改写。
     */
    public static boolean decimate(Path file, double targetHz, int skeletonPerDay) throws IOException {
        List<Segment> segments = read(file);
        int total = 0;
        for (Segment s : segments) {
            total += s.samples.size();
        }
        if (total <= skeletonPerDay) {
            return false;
        }
        boolean changed = false;
        for (Segment s : segments) {
            if (s.samples.size() <= 2 || s.hz <= targetHz + 0.01) {
                continue;
            }
            List<Sample> simplified = simplify(s.samples, SIMPLIFY_TOLERANCE);
            // 骨架下限：几何简化不能把一天抽到 skeleton 点以下（设计文档 §5.5 的"下限"）
            int floor = Math.min(skeletonPerDay, s.samples.size());
            if (simplified.size() < floor) {
                simplified = keepAtLeast(s.samples, floor);
            }
            s.samples = simplified;
            s.hz = Math.min(s.hz, targetHz);
            changed = true;
        }
        if (changed) {
            write(file, segments);
        }
        return changed;
    }

    /** Ramer–Douglas–Peucker（3D 位置；保留转弯，不级联丢弃） */
    public static List<Sample> simplify(List<Sample> points, double tolerance) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        // 朝向突变的点强制保留：只按位置做 RDP 会把"站着转头"全删掉
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < points.size() - 1; i++) {
            if (angleDelta(points.get(i - 1).yaw(), points.get(i).yaw()) > 15
                    || angleDelta(points.get(i - 1).pitch(), points.get(i).pitch()) > 15) {
                anchors.add(i);
                keep[i] = true;
            }
        }
        anchors.add(points.size() - 1);
        for (int i = 0; i + 1 < anchors.size(); i++) {
            simplify(points, anchors.get(i), anchors.get(i + 1), tolerance * tolerance, keep);
        }
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    private static float angleDelta(float a, float b) {
        return Math.abs(((b - a + 540f) % 360f) - 180f);
    }

    private static void simplify(List<Sample> points, int from, int to, double tol2, boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        Sample a = points.get(from);
        Sample b = points.get(to);
        double max = -1;
        int index = -1;
        for (int i = from + 1; i < to; i++) {
            double d = distanceSqToSegment(points.get(i), a, b);
            if (d > max) {
                max = d;
                index = i;
            }
        }
        if (max > tol2) {
            keep[index] = true;
            simplify(points, from, index, tol2, keep);
            simplify(points, index, to, tol2, keep);
        }
    }

    private static double distanceSqToSegment(Sample p, Sample a, Sample b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double abz = b.z() - a.z();
        double apx = p.x() - a.x();
        double apy = p.y() - a.y();
        double apz = p.z() - a.z();
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 <= 1e-9 ? 0 : (apx * abx + apy * aby + apz * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = apx - abx * t;
        double dy = apy - aby * t;
        double dz = apz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 骨架兜底：RDP 结果低于下限时，按均匀间隔补回原样本（只用于"至少保留 N 点"的下限） */
    private static List<Sample> keepAtLeast(List<Sample> points, int floor) {
        int n = points.size();
        if (n <= floor) {
            return new ArrayList<>(points);
        }
        List<Sample> out = new ArrayList<>(floor);
        double step = (n - 1) / (double) Math.max(1, floor - 1);
        for (int i = 0; i < floor; i++) {
            int idx = (int) Math.round(i * step);
            out.add(points.get(Math.min(idx, n - 1)));
        }
        return out;
    }

    public static long countSamples(Path file) {
        try {
            long n = 0;
            for (Segment s : read(file)) {
                n += s.samples.size();
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- varint / 定点工具 ----

    public static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    public static int readVarInt(byte[] b, int[] pos) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length || shift > 28) {
                throw new EOFException("varint 截断/溢出");
            }
            int x = b[pos[0]++] & 0xFF;
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    public static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    public static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    public static byte quantizeAngle(float deg) {
        int q = Math.round(deg / DEG_PER_UNIT);
        q = ((q % 256) + 256) % 256;
        return (byte) (q > 127 ? q - 256 : q);
    }

    public static float dequantizeAngle(byte b) {
        return b * DEG_PER_UNIT;
    }

    private static byte readByte(byte[] b, int[] pos) throws IOException {
        if (pos[0] >= b.length) {
            throw new EOFException("track 样本截断");
        }
        return b[pos[0]++];
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
}
