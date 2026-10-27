package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 差异库：按维度、按区块记录方块变化（变化前状态），用于倒流时逐格恢复。
 * <p>
 * 存储：{@code diff/<窗口>/<命名空间>/<维度路径>/r.<区域>/<cx>_<cz>.dat}，
 * 例如 {@code diff/current/minecraft/overworld/r.0.0/0_0.dat}。
 * 维度目录由调用方（{@link DiffRecorder}/{@link DiffRestore}）提供，本类只负责一个维度根目录；
 * 这样下界/末地的记录不会与主世界同坐标碰撞。
 * <p>
 * 单条记录定长 24 字节：4B blockStateId + 4B x + 4B y + 4B z + 8B timeMs。
 * <p>
 * 兼容旧布局：v0.10.0 及以前所有维度写在 {@code diff/<窗口>/r.<rx>.<rz>/} 下，没有维度层。
 * {@link #migrateLegacy(Path)} 会把这些数据迁入 {@code minecraft/overworld/}，可重复执行。
 * <p>
 * 并发安全：写入用全局锁；记录先进入内存缓冲，达到阈值或显式 {@link #flush()} 才批量落盘。
 */
public class DiffStore {
    private static final int RECORD_SIZE = 24; // 4 + 4 + 4 + 4 + 8
    /** 内存缓冲阈值：超过后自动落盘（避免每条一次磁盘 IO 卡死主线程） */
    private static final int FLUSH_THRESHOLD = 4096;
    /** 旧布局迁移目标维度：旧版本没有维度层，只能确定这些记录属于主世界 */
    public static final String OVERWORLD_DIM = "minecraft/overworld";
    /** 旧布局的区块目录名：r.<regionX>.<regionZ> */
    private static final Pattern LEGACY_REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+");

    private final Path root;
    /**
     * 是否把新写入的记录同步放进内存缓存。
     * <p>
     * 只有“恢复源”库需要缓存（restoreRound 反复读同一批区块）；常开的 current 库在生产路径
     * 从不读缓存，若也缓存，所有历史改动会一直留在堆里直到重启/重置窗口。
     */
    private final boolean cacheWrites;
    private final ReentrantLock globalLock = new ReentrantLock();
    /** 每区块内存缓冲（尚未落盘的记录，按区块 key 分组） */
    private final Map<Long, java.io.ByteArrayOutputStream> pending = new HashMap<>();
    private int pendingCount;
    /** 每区块内存缓存（仅恢复源使用；首次读盘后入缓存，后续零磁盘 IO） */
    private final Map<Long, List<Edit>> cache = new HashMap<>();

    private DiffStore(Path root, boolean cacheWrites) {
        this.root = root;
        this.cacheWrites = cacheWrites;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 diff 库目录 " + root, e);
        }
    }

    /** 打开/新建一个差异库（默认缓存新写入，兼容旧调用） */
    public static DiffStore open(Path root) {
        return new DiffStore(root, true);
    }

    /** 打开/新建一个差异库；{@code cacheWrites=false} 用于常开的 current 窗口，避免内存无界增长 */
    public static DiffStore open(Path root, boolean cacheWrites) {
        return new DiffStore(root, cacheWrites);
    }

    /** 恢复库：按赛季 + 维度打开（会自动迁移该赛季目录的旧布局） */
    public static DiffStore forSeason(Path configDir, int seasonId, String dimKey) {
        Path seasonRoot = configDir.resolve("diff").resolve("s" + seasonId);
        migrateLegacy(seasonRoot);
        return new DiffStore(seasonRoot.resolve(dimKey), true);
    }

    /** 兼容入口：按赛季目录打开主世界恢复库 */
    public static DiffStore forSeason(Path configDir, int seasonId) {
        return forSeason(configDir, seasonId, OVERWORLD_DIM);
    }

    public Path root() {
        return root;
    }

    private Path fileFor(int cx, int cz) {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        return root.resolve("r." + rx + "." + rz).resolve(cx + "_" + cz + ".dat");
    }

    /**
     * 把旧布局（{@code <窗口>/r.<rx>.<rz>/*.dat}）迁移到
     * {@code <窗口>/minecraft/overworld/r.<rx>.<rz>/*.dat}。
     * <p>
     * 可重复执行：目标文件已存在时按追加合并（记录按时间戳排序，追加顺序不影响恢复结果）。
     * 只迁移 {@code r.<数字>.<数字>} 目录，不会误碰新的维度目录。
     */
    public static void migrateLegacy(Path windowRoot) {
        if (windowRoot == null || !Files.isDirectory(windowRoot)) {
            return;
        }
        Path targetDim = windowRoot.resolve(OVERWORLD_DIM);
        int moved = 0;
        try (var dirs = Files.newDirectoryStream(windowRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)
                        || !LEGACY_REGION.matcher(dir.getFileName().toString()).matches()) {
                    continue;
                }
                Path targetDir = targetDim.resolve(dir.getFileName());
                try (var files = Files.newDirectoryStream(dir, "*.dat")) {
                    for (Path f : files) {
                        Path target = targetDir.resolve(f.getFileName());
                        Files.createDirectories(target.getParent());
                        if (Files.exists(target)) {
                            appendFile(f, target);
                            Files.deleteIfExists(f);
                        } else {
                            Files.move(f, target);
                        }
                        moved++;
                    }
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (DirectoryNotEmptyException ignored) {
                    // 目录里还有非 .dat 文件：保留，避免误删
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] diff 旧布局迁移失败（保留原数据，下次再试）: {}",
                    e.getMessage());
            return;
        }
        if (moved > 0) {
            Samsara.LOGGER.info("[周目管理 samsara] diff 旧布局已迁移: {} 个区块文件 → {}", moved,
                    targetDim.toAbsolutePath().normalize());
        }
    }

    /**
     * 把 {@code src} 窗口的全部记录合并进 {@code dst}（同路径文件追加，目标不存在则移动）。
     * 用于“中止后重新倒流”把冻结窗口并回 current，以及冻结时目标库已存在的兜底。
     * <p>
     * 只处理 {@code .dat} 文件；调用方负责在成功后删除 {@code src} 目录。
     */
    public static void mergeWindows(Path src, Path dst) throws IOException {
        if (src == null || dst == null || !Files.isDirectory(src)) {
            return;
        }
        migrateLegacy(src);
        migrateLegacy(dst);
        Files.createDirectories(dst);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(src)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".dat"))
                    .toList();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path f : files) {
            Path target = dst.resolve(src.relativize(f));
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                appendFile(f, target);
                Files.delete(f);
            } else {
                Files.move(f, target);
            }
        }
    }

    private static void appendFile(Path src, Path dst) throws IOException {
        try (InputStream in = Files.newInputStream(src);
             OutputStream out = Files.newOutputStream(dst,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            in.transferTo(out);
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
            // 只有恢复源需要缓存；current 窗口的缓存永远没人读，缓存它等于内存泄漏
            if (cacheWrites) {
                cache.computeIfAbsent(key, k -> new ArrayList<>()).add(new Edit(x, y, z, stateId, timeMs));
            }
            if (pendingCount >= FLUSH_THRESHOLD) {
                flushLocked();
            }
        } finally {
            globalLock.unlock();
        }
    }

    /** 把内存缓冲一次性落盘（停服/倒流开始/定期 flush 调用，确保数据可读） */
    public void flush() {
        globalLock.lock();
        try {
            flushLocked();
        } finally {
            globalLock.unlock();
        }
    }

    /** 尚未落盘的记录数（供状态展示；落盘后归零） */
    public int pendingCount() {
        globalLock.lock();
        try {
            return pendingCount;
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
                Files.write(f, e.getValue(), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
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

    /** 统计本维度记录总数（含尚未落盘的缓冲） */
    public long count() {
        return countAll(root) + pendingCount();
    }

    /** 统计一个窗口下全部维度（含所有维度目录）的磁盘记录数；不含内存缓冲 */
    public static long countAll(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        final long[] total = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".dat"))
                    .forEach(p -> {
                        try {
                            total[0] += Files.size(p) / RECORD_SIZE;
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException | java.io.UncheckedIOException e) {
            Samsara.LOGGER.error("diff 统计失败: {}", e.getMessage());
        }
        return total[0];
    }
}
