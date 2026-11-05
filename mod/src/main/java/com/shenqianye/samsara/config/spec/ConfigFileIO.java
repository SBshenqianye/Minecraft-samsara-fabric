package com.shenqianye.samsara.config.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** 配置编辑器共用的文件工具：哈希、原子写、写前备份。 */
public final class ConfigFileIO {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ConfigFileIO() {
    }

    /** 文件 SHA-256；文件不存在时返回空串。 */
    public static String revision(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    public static String read(Path file) throws IOException {
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 临时文件 + 原子替换；文件系统不支持原子移动时退化为普通替换。 */
    public static void atomicWrite(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 写前备份到 {@code backups/samsara/config-editor/<时间戳>/<相对路径>}。 */
    public static Path backup(Path file, Path serverRoot) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Path root = serverRoot == null ? file.getParent() : serverRoot;
        String stamp = LocalDateTime.now().format(STAMP);
        Path base = root.resolve("backups").resolve("samsara").resolve("config-editor").resolve(stamp);
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            relative = file.getFileName();
        }
        if (relative.startsWith("..")) {
            relative = file.getFileName();
        }
        Path target = base.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
