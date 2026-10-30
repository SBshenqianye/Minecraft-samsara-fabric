package com.shenqianye.samsara.storage;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 旧 {@code config/samsara/} 数据的迁移器（F-119）。
 *
 * <p>原则：
 * <ul>
 *   <li><b>幂等</b>：有版本标记且新根存在时跳过；失败可重试；</li>
 *   <li><b>先备份、不删源</b>：旧目录改名为 {@code config/samsara.legacy} 保留；</li>
 *   <li><b>冲突保留两份</b>：目标已有同名文件时，源文件加时间戳后缀，日志列出；</li>
 *   <li><b>大文件优先 move</b>：跨盘失败回退到复制 + 大小/SHA-256 校验。</li>
 * </ul>
 *
 * <p>本类不依赖 Fabric/Minecraft，便于用临时目录做单测。
 */
public final class StorageMigration {
    public static final int CURRENT_VERSION = 1;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 世界数据条目（旧 config/samsara 下的名字）。 */
    public static final List<String> WORLD_ENTRIES = List.of(
            "season.json", "rewind.json", "pending-delete.json", "pending-teleports.json",
            "claims", "activity", "track", "diff", "archive", "backups", "audit",
            "boundary-toggles.json", "debug-delete.log");
    /** 全局数据条目。 */
    public static final List<String> GLOBAL_ENTRIES = List.of(
            "global", "identity.json", "auth.json", "audit-index.json");

    private StorageMigration() {
    }

    /**
     * 执行/重试世界数据迁移。
     *
     * @param legacy      旧配置目录（可为 null = 没有旧数据）
     * @param sourceFallback 旧目录无法整体改名时的源（通常仍是 configRoot）
     * @param worldDataRoot 新世界数据根
     * @param globalDataRoot 新全局数据根
     * @param currentVersion storage.yml 里的迁移版本
     * @return 人类可读结果
     */
    public static String migrate(Path legacy, Path sourceFallback, Path worldDataRoot,
                                 Path globalDataRoot, int currentVersion) {
        List<String> notes = new ArrayList<>();
        try {
            Files.createDirectories(worldDataRoot);
            Files.createDirectories(globalDataRoot);
        } catch (IOException e) {
            return "失败：无法创建新数据目录（" + e.getMessage() + "）";
        }
        boolean already = currentVersion >= CURRENT_VERSION && Files.exists(worldDataRoot);
        Path source = legacy != null && Files.exists(legacy) ? legacy : sourceFallback;
        if (already) {
            return "已迁移（版本 " + currentVersion + "），跳过";
        }
        if (source == null || !Files.exists(source)) {
            return "全新安装：新数据目录已就绪";
        }

        int moved = 0;
        for (String name : WORLD_ENTRIES) {
            Path from = source.resolve(name);
            if (!Files.exists(from)) {
                continue;
            }
            Path to = worldDataRoot.resolve(name);
            try {
                if ("track".equals(name)) {
                    moved += migrateTrack(from, worldDataRoot.resolve("track"), notes);
                } else if ("diff".equals(name)) {
                    moved += migrateDiff(from, worldDataRoot.resolve("diff"), notes);
                } else if ("global".equals(name)) {
                    moved += mergeMove(from, globalDataRoot, notes);
                } else {
                    moved += mergeMove(from, to, notes);
                }
            } catch (IOException e) {
                notes.add(name + " 迁移失败：" + e.getMessage() + "（源保留）");
            }
        }
        // 旧版圈地文件是根目录平铺的 claims.json / geometry.json，新布局收进 claims/
        for (String name : List.of("claims.json", "geometry.json")) {
            Path from = source.resolve(name);
            if (!Files.exists(from)) {
                continue;
            }
            try {
                moved += mergeMove(from, worldDataRoot.resolve("claims").resolve(name), notes);
            } catch (IOException e) {
                notes.add(name + " 迁移失败：" + e.getMessage() + "（源保留）");
            }
        }

        // 旧版散落的全局文件
        for (String name : GLOBAL_ENTRIES) {
            Path from = source.resolve(name);
            if (!Files.exists(from)) {
                continue;
            }
            try {
                if ("global".equals(name)) {
                    for (Path child : list(from)) {
                        moved += mergeMove(child,
                                globalDataRoot.resolve(child.getFileName().toString()), notes);
                    }
                    try {
                        deleteTree(from);
                    } catch (IOException ignored) {
                    }
                } else {
                    moved += mergeMove(from, globalDataRoot.resolve(name), notes);
                }
            } catch (IOException e) {
                notes.add(name + " 迁移失败：" + e.getMessage() + "（源保留）");
            }
        }

        String conflicts = notes.isEmpty() ? "" : "；" + String.join("；", notes);
        Samsara.LOGGER.info("[周目管理 samsara] 数据迁移完成：{} 项 → {}（源 {}）{}",
                moved, worldDataRoot, source, conflicts);
        return (moved > 0 ? "已迁移 " + moved + " 项" : "没有需要迁移的数据")
                + " → " + worldDataRoot + conflicts;
    }

    /**
     * 全局数据双向迁移（F-128）。
     *
     * @return null = 成功；否则为错误说明（调用方保持原开关）
     */
    public static String moveGlobal(Path from, Path to) {
        if (from == null || to == null || from.toAbsolutePath().normalize()
                .equals(to.toAbsolutePath().normalize())) {
            return null;
        }
        if (!Files.exists(from)) {
            try {
                Files.createDirectories(to);
                return null;
            } catch (IOException e) {
                return e.getMessage();
            }
        }
        List<String> notes = new ArrayList<>();
        List<Path> created = new ArrayList<>();
        try {
            Files.createDirectories(to);
            mergeCopy(from, to, "", notes, created);
        } catch (IOException e) {
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
            return e.getMessage() + "（已回滚新建文件，源目录保留）";
        }
        try {
            deleteTree(from);
        } catch (IOException e) {
            return "复制成功但删除源失败：" + e.getMessage() + "（源与目标都在，未切换开关）";
        }
        Samsara.LOGGER.info("[周目管理 samsara] 全局数据已迁移：{} → {}{}",
                from, to, notes.isEmpty() ? "" : "（" + String.join("；", notes) + "）");
        return null;
    }

    // ---- track / diff 旧世界层剥离 ----

    private static int migrateTrack(Path from, Path to, List<String> notes) throws IOException {
        Files.createDirectories(to);
        int moved = 0;
        List<Path> children = list(from);
        Path worldLayer = findWorldLayer(children, to);
        if (worldLayer != null) {
            // track/<世界>/... → track/...
            for (Path child : list(worldLayer)) {
                moved += mergeMove(child, to.resolve(child.getFileName().toString()), notes);
            }
            notes.add("track 已剥离旧世界层 " + worldLayer.getFileName());
            try {
                deleteTree(worldLayer);
            } catch (IOException ignored) {
            }
        } else {
            for (Path child : children) {
                moved += mergeMove(child, to.resolve(child.getFileName().toString()), notes);
            }
        }
        try {
            deleteTree(from);
        } catch (IOException ignored) {
        }
        return moved;
    }

    private static int migrateDiff(Path from, Path to, List<String> notes) throws IOException {
        Files.createDirectories(to);
        int moved = 0;
        for (Path window : list(from)) {
            if (!Files.isDirectory(window)) {
                moved += mergeMove(window, to.resolve(window.getFileName().toString()), notes);
                continue;
            }
            Path targetWindow = to.resolve(window.getFileName().toString());
            Files.createDirectories(targetWindow);
            Path worldLayer = findWorldLayer(list(window), targetWindow);
            if (worldLayer != null) {
                for (Path child : list(worldLayer)) {
                    moved += mergeMove(child, targetWindow.resolve(child.getFileName().toString()),
                            notes);
                }
                notes.add("diff/" + window.getFileName() + " 已剥离旧世界层 "
                        + worldLayer.getFileName());
                try {
                    deleteTree(worldLayer);
                } catch (IOException ignored) {
                }
            } else {
                for (Path child : list(window)) {
                    moved += mergeMove(child, targetWindow.resolve(child.getFileName().toString()),
                            notes);
                }
            }
            try {
                deleteTree(window);
            } catch (IOException ignored) {
            }
        }
        try {
            deleteTree(from);
        } catch (IOException ignored) {
        }
        return moved;
    }

    /** 旧布局的 `<世界>` 层：窗口/根下唯一一个不是已知子目录的目录。 */
    private static Path findWorldLayer(List<Path> children, Path target) {
        for (Path child : children) {
            if (!Files.isDirectory(child)) {
                continue;
            }
            String name = child.getFileName().toString();
            if (name.startsWith("cycle") || name.equals("live") || name.equals("minecraft")
                    || name.equals("dimensions") || name.equals("region")
                    || name.matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            // 目标里已经有同名子目录，说明这是数据目录而不是世界层
            if (Files.exists(target.resolve(name))) {
                continue;
            }
            return child;
        }
        return null;
    }

    /**
     * 剥离 {@code track/<世界>/...} 世界层（启动时调用，幂等）。
     * 只把“看起来像世界层”的目录（非 {@code cycle*}、非日期、非文件）展开到 track 根。
     *
     * @return 处理的世界层目录数
     */
    public static int stripTrackWorldLayer(Path trackRoot) {
        if (trackRoot == null || !Files.isDirectory(trackRoot)) {
            return 0;
        }
        int stripped = 0;
        List<String> notes = new ArrayList<>();
        List<Path> children;
        try {
            children = list(trackRoot);
        } catch (IOException e) {
            return 0;
        }
        for (Path child : children) {
            if (!Files.isDirectory(child)) {
                continue;
            }
            String name = child.getFileName().toString();
            if (name.startsWith("cycle") || name.matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            try {
                for (Path grandchild : list(child)) {
                    mergeMove(grandchild, trackRoot.resolve(grandchild.getFileName().toString()),
                            notes);
                }
                deleteTree(child);
                stripped++;
                Samsara.LOGGER.info("[周目管理 samsara] track 已剥离旧世界层 {}", name);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] track 世界层剥离失败（保留原数据）: {}",
                        e.getMessage());
            }
        }
        return stripped;
    }

    // ---- 文件移动 / 合并 ----

    /** 把 src 移到 dst；目标已存在时递归合并，冲突文件保留两份。返回移动的条目数。 */
    static int mergeMove(Path src, Path dst, List<String> notes) throws IOException {
        if (!Files.exists(dst)) {
            moveOrCopy(src, dst);
            return 1;
        }
        if (Files.isDirectory(src) && Files.isDirectory(dst)) {
            int count = 0;
            for (Path child : list(src)) {
                count += mergeMove(child, dst.resolve(child.getFileName().toString()), notes);
            }
            try {
                Files.deleteIfExists(src);
            } catch (IOException ignored) {
            }
            return count;
        }
        Path conflict = conflictName(dst);
        moveOrCopy(src, conflict);
        notes.add("冲突保留两份：" + dst.getFileName() + " + " + conflict.getFileName());
        return 1;
    }

    /** 只复制（不删源），用于全局数据可回滚迁移。 */
    private static void mergeCopy(Path src, Path dst, String relative, List<String> notes,
                                  List<Path> created) throws IOException {
        if (Files.isDirectory(src)) {
            if (!Files.exists(dst)) {
                Files.createDirectories(dst);
                created.add(dst);
            }
            for (Path child : list(src)) {
                mergeCopy(child, dst.resolve(child.getFileName().toString()),
                        relative + "/" + child.getFileName(), notes, created);
            }
            return;
        }
        if (Files.exists(dst)) {
            Path conflict = conflictName(dst);
            copyVerify(src, conflict);
            created.add(conflict);
            notes.add("冲突保留两份：" + dst.getFileName() + " + " + conflict.getFileName());
            return;
        }
        copyVerify(src, dst);
        created.add(dst);
    }

    /** 优先 move；跨盘/不支持时复制 + 校验大小与 SHA-256。 */
    static void moveOrCopy(Path src, Path dst) throws IOException {
        if (dst.getParent() != null) {
            Files.createDirectories(dst.getParent());
        }
        try {
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dst);
            }
            return;
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException moveFailure) {
            Samsara.LOGGER.info("[周目管理 samsara] move 失败，回退复制：{} → {}（{}）",
                    src, dst, moveFailure.getMessage());
        }
        if (Files.isDirectory(src)) {
            copyTree(src, dst);
        } else {
            copyVerify(src, dst);
        }
        deleteTree(src);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        for (Path child : list(src)) {
            Path target = dst.resolve(child.getFileName().toString());
            if (Files.isDirectory(child)) {
                copyTree(child, target);
            } else {
                copyVerify(child, target);
            }
        }
    }

    private static void copyVerify(Path src, Path dst) throws IOException {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(src) != Files.size(dst)) {
            throw new IOException("复制后大小不一致：" + src + " → " + dst);
        }
        String a = sha256(src);
        String b = sha256(dst);
        if (!a.equals(b)) {
            throw new IOException("复制后哈希不一致：" + src + " → " + dst);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digest = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            digest.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static Path conflictName(Path dst) {
        String name = dst.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return dst.resolveSibling(base + ".conflict-" + LocalDateTime.now().format(TS) + ext);
    }

    private static List<Path> list(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.sorted().toList();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (Files.isDirectory(dir)) {
            for (Path child : list(dir)) {
                deleteTree(child);
            }
        }
        Files.deleteIfExists(dir);
    }
}
