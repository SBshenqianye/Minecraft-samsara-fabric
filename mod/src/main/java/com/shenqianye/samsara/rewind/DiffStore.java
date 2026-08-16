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
    private static DiffStore instance;

    private final Path root;
    private final Map<Long, ReentrantLock> perChunkLocks = new HashMap<>();
    private final ReentrantLock globalLock = new ReentrantLock();

    private DiffStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 diff 库目录 " + root, e);
        }
    }

    /** 初始化/复用单例（season 切换时换目录） */
    public static synchronized DiffStore forSeason(Path configDir, int seasonId) {
        Path d = configDir.resolve("diff").resolve("s" + seasonId);
        if (instance != null && instance.root.equals(d)) {
            return instance;
        }
        instance = new DiffStore(d);
        return instance;
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

    /** 追加一条方块变化记录（stateId = 变化前方块状态数值 id，恢复时还原此状态；0=空气合法） */
    public void record(int x, int y, int z, int stateId, long timeMs) {
        int cx = x >> 4;
        int cz = z >> 4;
        ReentrantLock lock = lockFor(cx, cz);
        lock.lock();
        try {
            Path f = fileFor(cx, cz);
            Files.createDirectories(f.getParent());
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
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
                raf.seek(raf.length());
                raf.write(rec);
            }
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] diff 记录失败 {}: {}", fileFor(cx, cz), e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** 解析一个区块文件的所有记录 */
    public List<Edit> readChunk(int cx, int cz) {
        Path f = fileFor(cx, cz);
        List<Edit> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
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
