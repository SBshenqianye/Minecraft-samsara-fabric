package com.shenqianye.samsara.season;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 世界导入的只读校验：来源是否在允许的根内、存档是否被占用、玩家数据有多少冲突。
 *
 * <p>从 {@code WorldStackAdmin} 抽出，便于用临时目录单测，不碰服务器状态。
 */
public final class WorldStackImportGuard {

    private WorldStackImportGuard() {
    }

    /** 来源路径是否在服务器根 / 世界父目录 / 显式导入根之内。 */
    public static boolean allowed(Path real, Path worldDir, Path serverRoot, List<Path> importRoots) {
        Path worldAbs = worldDir.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        if (serverRoot != null) {
            roots.add(serverRoot);
        } else if (worldAbs.getParent() != null) {
            roots.add(worldAbs.getParent());
        }
        if (importRoots != null) {
            roots.addAll(importRoots);
        }
        for (Path root : roots) {
            try {
                Path canonical = root.toRealPath();
                if (real.startsWith(canonical)) {
                    return true;
                }
            } catch (IOException ignored) {
                if (real.startsWith(root.toAbsolutePath().normalize())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** session.lock 已被其它进程占用时返回 true。 */
    public static boolean sessionLocked(Path lock) {
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            return ignored == null;
        } catch (Exception e) {
            return true;
        }
    }

    public static int playerConflicts(Path worldDir, Path source) {
        return conflictFiles(worldDir, source).size();
    }

    /** 源 players/data 下与目标同名的文件（最多列 20 个，避免预览过长）。 */
    public static List<String> conflictFiles(Path worldDir, Path source) {
        List<String> out = new ArrayList<>();
        Path src = source.resolve("players/data");
        Path dst = worldDir.resolve("players/data");
        if (!Files.isDirectory(src)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(src)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                Path rel = src.relativize(file);
                if (Files.exists(dst.resolve(rel))) {
                    out.add(rel.toString().replace('\\', '/'));
                }
            });
        } catch (IOException ignored) {
            // 预览阶段不阻断；真正复制时逐文件记录
        }
        out.sort(Comparator.naturalOrder());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }
}
