package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.DiffStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 玩家轨迹的目录布局与迁移（纯文件系统，便于单测）。
 * <p>
 * 现行布局（按周目隔离）：
 * <pre>
 * track/&lt;世界&gt;/cycle&lt;周目id&gt;/&lt;YYYY-MM-DD&gt;/&lt;uuid&gt;.bin
 * </pre>
 * 无周目时用 {@code cycle0}。世界键 = 服务器世界目录名（与 diff 库一致，
 * 由 {@link DiffStore#sanitizeWorldKey(String)} 清洗）。
 * <p>
 * 迁移（幂等、不丢数据）：
 * <ul>
 *   <li>v0.12.0 布局 {@code track/<世界>/<日期>/} → {@code track/<世界>/cycle<id>/<日期>/}；</li>
 *   <li>更早的无世界层布局 {@code track/<日期>/} → {@code track/<世界>/cycle<id>/<日期>/}。</li>
 * </ul>
 * 同名目标文件按“多段合并”处理（track 文件本身允许多段），不会覆盖旧记录。
 */
public final class TrackLayout {
    /** 周目目录前缀：cycle<id> */
    public static final String CYCLE_PREFIX = "cycle";

    private TrackLayout() {
    }

    public static String cycleDirName(int cycleId) {
        return CYCLE_PREFIX + Math.max(0, cycleId);
    }

    /** track/&lt;世界&gt; */
    public static Path worldRoot(Path trackRoot, String worldKey) {
        return trackRoot.resolve(DiffStore.sanitizeWorldKey(worldKey));
    }

    /** track/&lt;世界&gt;/cycle&lt;id&gt; */
    public static Path cycleDir(Path worldRoot, int cycleId) {
        return worldRoot.resolve(cycleDirName(cycleId));
    }

    /** track/&lt;世界&gt;/cycle&lt;id&gt;/&lt;日期&gt; */
    public static Path dayDir(Path worldRoot, int cycleId, LocalDate day) {
        return cycleDir(worldRoot, cycleId).resolve(day.toString());
    }

    /** track/&lt;世界&gt;/cycle&lt;id&gt;/&lt;日期&gt;/&lt;uuid&gt;.bin */
    public static Path file(Path worldRoot, int cycleId, LocalDate day, UUID uuid) {
        return dayDir(worldRoot, cycleId, day).resolve(uuid + ".bin");
    }

    /** 目录名是否是 {@code YYYY-MM-DD} */
    public static boolean isDateDir(String name) {
        try {
            LocalDate.parse(name);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 目录名是否是 {@code cycle<id>}，是则返回 id，否则 -1 */
    public static int cycleIdOf(String name) {
        if (name == null || !name.startsWith(CYCLE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(CYCLE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 把 {@code track/<世界>/<日期>/} 迁入 {@code track/<世界>/cycle<id>/<日期>/}。
     *
     * @return 迁移的日期目录数（0 = 无需迁移）
     */
    public static int migrateWorldLayout(Path worldRoot, int cycleId) {
        if (worldRoot == null || !Files.isDirectory(worldRoot)) {
            return 0;
        }
        List<Path> dates = new ArrayList<>();
        try (Stream<Path> list = Files.list(worldRoot)) {
            list.filter(Files::isDirectory)
                    .filter(p -> isDateDir(p.getFileName().toString()))
                    .forEach(dates::add);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹周目层扫描失败: {}", e.getMessage());
            return 0;
        }
        int moved = 0;
        for (Path src : dates) {
            Path dst = cycleDir(worldRoot, cycleId).resolve(src.getFileName().toString());
            if (mergeDayDir(src, dst)) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * 把更早的无世界层布局 {@code track/<日期>/} 迁入 {@code track/<世界>/cycle<id>/<日期>/}。
     *
     * @return 迁移的日期目录数（0 = 无需迁移）
     */
    public static int migrateLegacyRoot(Path trackRoot, Path worldRoot, int cycleId) {
        if (trackRoot == null || worldRoot == null || !Files.isDirectory(trackRoot)) {
            return 0;
        }
        List<Path> dates = new ArrayList<>();
        try (Stream<Path> list = Files.list(trackRoot)) {
            list.filter(Files::isDirectory)
                    .filter(p -> isDateDir(p.getFileName().toString()))
                    .forEach(dates::add);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹旧布局扫描失败: {}", e.getMessage());
            return 0;
        }
        int moved = 0;
        for (Path src : dates) {
            Path dst = cycleDir(worldRoot, cycleId).resolve(src.getFileName().toString());
            if (mergeDayDir(src, dst)) {
                moved++;
            }
        }
        return moved;
    }

    /** 合并两个日期目录（同名 .bin 追加段），成功后删除源目录。返回是否发生了移动/合并 */
    private static boolean mergeDayDir(Path src, Path dst) {
        try {
            Files.createDirectories(dst);
            List<Path> files;
            try (Stream<Path> list = Files.list(src)) {
                files = list.filter(Files::isRegularFile).toList();
            }
            for (Path f : files) {
                Path to = dst.resolve(f.getFileName());
                if (Files.exists(to)) {
                    mergeTrackFile(f, to);
                } else {
                    Files.move(f, to);
                }
            }
            try {
                Files.deleteIfExists(src);
            } catch (IOException ignored) {
                // 目录里可能还有非文件条目：保留，下次再试
            }
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹日期目录迁移失败 {} → {}（保留原数据）: {}",
                    src, dst, e.getMessage());
            return false;
        }
    }

    /** 同名轨迹文件合并：两段都保留（track 文件允许多段） */
    public static void mergeTrackFile(Path src, Path dst) {
        try {
            List<TrackStore.Segment> segments = new ArrayList<>(TrackStore.read(dst));
            segments.addAll(TrackStore.read(src));
            TrackStore.write(dst, segments);
            Files.deleteIfExists(src);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹旧文件合并失败 {}: {}", src, e.getMessage());
        }
    }
}
