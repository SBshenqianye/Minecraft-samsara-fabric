package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 档3 差异库：按区块记录方块变化（变化前状态），用于倒流时逐格恢复。
 * <p>
 * 存储：每区块一个二进制补丁文件 {@code diff/s<season>/r.<区域>/<cx>_<cz>.dat}，零依赖。
 * <p>
 * 单条记录定长 24 字节：4B blockStateId + 4B x + 4B y + 4B z + 8B timeMs。
 * <p>
 * 并发安全：写入用每区块锁。
 */
public class DiffStore {
    private static final int RECORD_SIZE = 24; // 4 + 4 + 4 + 4 + 8
    /** 内存缓冲阈值：超过后自动落盘（避免每条一次磁盘 IO 卡死主线程） */
    private static final int FLUSH_THRESHOLD = 4096;
    private static DiffStore instance;

    private final Path root;
    private final Map<Long, ReentrantLock> perChunkLocks = new HashMap<>();
    private final ReentrantLock globalLock = new ReentrantLock();
    /** 每区块内存缓冲（尚未落盘的记录，按区块 key 分组） */
    private final Map<Long, java.io.ByteArrayOutputStream> pending = new HashMap<>();
    private int pendingCount;
    /** 每区块内存缓存（已读入/已记录的 Edit，restoreRound 只读内存，不再反复读磁盘） */
    private final Map<Long, List<Edit>> cache = new HashMap<>();

    private DiffStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 diff 库目录 " + root, e);
        }
    }

    /** 打开/新建一个差异库（非单例：记录库 diff/current 与恢复库 diff/s<N> 可并存） */
    public static DiffStore open(Path root) {
        return new DiffStore(root);
    }

    /** 兼容入口：按赛季目录打开（恢复库用）；等价于 {@link #open(Path)} */
    public static synchronized DiffStore forSeason(Path configDir, int seasonId) {
        return new DiffStore(configDir.resolve("diff").resolve("s" + seasonId));
    }

    public static DiffStore current() {
        return instance;
    }

    public Path root() {
        return root;
    }

    private Path fileFor(int cx, int cz) {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        return root.resolve("r." + rx + "." + rz).resolve(cx + "_" + cz + ".dat");
    }

    private ReentrantLock lockFor(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        globalLock.lock();
        try {
            return perChunkLocks.computeIfAbsent(key, k -> new ReentrantLock());
        } finally {
            globalLock.unlock();
        }
    }

    /**
     * 追加一条方块变化记录（stateId = 变化前方块状态数值 id，恢复时还原此状态；0=空气合法）。
     * 先写入内存缓冲，达到阈值或显式 flush() 时才批量落盘（避免每条一次磁盘 IO 阻塞服务器主线程）。
     */
    public void record(int x, int y, int z, int stateId, long timeMs) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        byte[] rec = new byte[RECORD_SIZE];
        rec[0] = (byte) (stateId >>> 24);
        rec[1] = (byte) (stateId >>> 16);
        rec[2] = (byte) (stateId >>> 8);
        rec[3] = (byte) stateId;
        rec[4] = (byte) (x >>> 24);
        rec[5] = (byte) (x >>> 16);
        rec[6] = (byte) (x >>> 8);
        rec[7] = (byte) x;
        rec[8] = (byte) (y >>> 24);
        rec[9] = (byte) (y >>> 16);
        rec[10] = (byte) (y >>> 8);
        rec[11] = (byte) y;
        rec[12] = (byte) (z >>> 24);
        rec[13] = (byte) (z >>> 16);
        rec[14] = (byte) (z >>> 8);
        rec[15] = (byte) z;
        for (int i = 0; i < 8; i++) {
            rec[16 + i] = (byte) (timeMs >>> (56 - i * 8));
        }
        globalLock.lock();
        try {
            java.io.ByteArrayOutputStream buf = pending.computeIfAbsent(key, k -> new java.io.ByteArrayOutputStream());
            buf.write(rec, 0, RECORD_SIZE);
            pendingCount++;
            // 同步更新内存缓存（restoreRound 只读缓存，不反复读磁盘）
            cache.computeIfAbsent(key, k -> new ArrayList<>()).add(new Edit(x, y, z, stateId, timeMs));
            if (pendingCount >= FLUSH_THRESHOLD) {
                flushLocked();
            }
        } finally {
            globalLock.unlock();
        }
    }

    /** 把内存缓冲一次性落盘（fake 注入/倒流开始前调用，确保数据立即可读） */
    public void flush() {
        globalLock.lock();
        try {
            flushLocked();
        } finally {
            globalLock.unlock();
        }
    }

    /** 前提：持有 globalLock。把 pending 缓冲按区块写入对应文件（追加）。 */
    private void flushLocked() {
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, byte[]> batches = new HashMap<>();
        for (Map.Entry<Long, java.io.ByteArrayOutputStream> e : pending.entrySet()) {
            batches.put(e.getKey(), e.getValue().toByteArray());
        }
        pending.clear();
        pendingCount = 0;
        for (Map.Entry<Long, byte[]> e : batches.entrySet()) {
            int cx = (int) (e.getKey() >>> 32);
            int cz = (int) (e.getKey().longValue());
            Path f = fileFor(cx, cz);
            try {
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue(), java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ex) {
                Samsara.LOGGER.error("[周目管理 samsara] diff 落盘失败 {}: {}", f, ex.getMessage());
            }
        }
    }

    /** 解析一个区块文件的所有记录（优先内存缓存；首次读盘后入缓存，后续零磁盘 IO） */
    public List<Edit> readChunk(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        globalLock.lock();
        try {
            List<Edit> cached = cache.get(key);
            if (cached != null) {
                return new ArrayList<>(cached); // 返回副本，避免调用方排序污染缓存
            }
        } finally {
            globalLock.unlock();
        }
        Path f = fileFor(cx, cz);
        List<Edit> out = new ArrayList<>();
        if (Files.exists(f)) {
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                long len = raf.length();
                byte[] buf = new byte[RECORD_SIZE];
                for (long pos = 0; pos + RECORD_SIZE <= len; pos += RECORD_SIZE) {
                    raf.seek(pos);
                    raf.readFully(buf);
                    int stateId = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
                    int x = ((buf[4] & 0xFF) << 24) | ((buf[5] & 0xFF) << 16) | ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
                    int y = ((buf[8] & 0xFF) << 24) | ((buf[9] & 0xFF) << 16) | ((buf[10] & 0xFF) << 8) | (buf[11] & 0xFF);
                    int z = ((buf[12] & 0xFF) << 24) | ((buf[13] & 0xFF) << 16) | ((buf[14] & 0xFF) << 8) | (buf[15] & 0xFF);
                    long time = 0;
                    for (int i = 0; i < 8; i++) {
                        time = (time << 8) | (buf[16 + i] & 0xFF);
                    }
                    out.add(new Edit(x, y, z, stateId, time));
                }
            } catch (IOException e) {
                Samsara.LOGGER.error("[周目管理 samsara] diff 读取失败 {}: {}", f, e.getMessage());
            }
        }
        globalLock.lock();
        try {
            cache.putIfAbsent(key, new ArrayList<>(out));
        } finally {
            globalLock.unlock();
        }
        return out;
    }

    /** 单个编辑记录 */
    public static class Edit {
        public final int x, y, z;
        public final int stateId;
        public final long timeMs;

        public Edit(int x, int y, int z, int stateId, long timeMs) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stateId = stateId;
            this.timeMs = timeMs;
        }
    }

    /** 统计记录总数（遍历所有区块文件） */
    public long count() {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        final long[] total = {0};
        try (var walk = Files.walk(root)) {
            var s = walk.filter(p -> p.getFileName().toString().endsWith(".dat"));
            s.forEach(p -> {
                try {
                    total[0] += Files.size(p) / RECORD_SIZE;
                } catch (IOException ignore) {
                }
            });
        } catch (IOException e) {
            Samsara.LOGGER.error("diff 统计失败: {}", e.getMessage());
        }
        return total[0];
    }
}
