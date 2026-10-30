package com.shenqianye.samsara.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 玩家数据同步事务日志。
 *
 * <p>流程：写 {@code prepared} → 备份目标 → 临时文件 + 原子替换 → 校验和 → {@code committed} → 清理。
 * 启动时 {@code prepared} 回滚到备份，{@code committed} 直接清理；无法确认一致性时保留 journal，
 * 由调用方把该组标记为 {@code sync-blocked}，绝不删除源数据。
 */
public final class IdentityJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final Path dir;

    public IdentityJournal(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** 写 prepared 记录；返回 journal 文件路径。 */
    public Path prepare(String group, UUID from, UUID to, Path target, Path backup,
                        String checksum) {
        Entry entry = new Entry();
        entry.group = group;
        entry.from = from == null ? null : from.toString();
        entry.to = to == null ? null : to.toString();
        entry.state = "prepared";
        entry.target = target == null ? null : target.toAbsolutePath().normalize().toString();
        entry.backup = backup == null ? null : backup.toAbsolutePath().normalize().toString();
        entry.checksum = checksum;
        entry.time = System.currentTimeMillis();
        Path file = dir.resolve(TS.format(LocalDateTime.now()) + "-"
                + (to == null ? "unknown" : to) + ".json");
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(entry), StandardCharsets.UTF_8);
            moveAtomic(tmp, file);
            return file;
        } catch (IOException e) {
            throw new RuntimeException("journal 写入失败：" + e.getMessage(), e);
        }
    }

    /** 标记 committed 并删除 journal（清理由提交路径完成）。 */
    public void commit(Path journalFile) {
        if (journalFile == null) {
            return;
        }
        try {
            Entry entry = read(journalFile);
            if (entry != null) {
                entry.state = "committed";
                Path tmp = journalFile.resolveSibling(journalFile.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(entry), StandardCharsets.UTF_8);
                moveAtomic(tmp, journalFile);
            }
            Files.deleteIfExists(journalFile);
        } catch (IOException ignored) {
            // 删不掉也不影响数据；启动恢复会按 committed 清理。
        }
    }

    /**
     * 启动恢复。
     *
     * @return rolledBack = 已按备份回滚的 prepared 数；cleaned = 已清理的 committed 数；
     *         blocked = 无法确认、需要 sync-blocked 的说明
     */
    public Recovery recover() {
        int rolledBack = 0;
        int cleaned = 0;
        List<String> blocked = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new Recovery(0, 0, List.of());
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : (Iterable<Path>) list::iterator) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                Entry entry = read(file);
                if (entry == null || entry.state == null) {
                    blocked.add("无法解析的 journal：" + file.getFileName());
                    continue;
                }
                if ("committed".equals(entry.state)) {
                    try {
                        Files.deleteIfExists(file);
                        cleaned++;
                    } catch (IOException e) {
                        blocked.add("committed journal 清理失败：" + file.getFileName());
                    }
                    continue;
                }
                if (!"prepared".equals(entry.state)) {
                    blocked.add("未知 journal 状态：" + file.getFileName());
                    continue;
                }
                Path target = entry.target == null ? null : Path.of(entry.target);
                Path backup = entry.backup == null ? null : Path.of(entry.backup);
                try {
                    if (backup != null && Files.exists(backup) && target != null) {
                        Files.createDirectories(target.getParent());
                        Path tmp = target.resolveSibling(target.getFileName() + ".rollback.tmp");
                        Files.copy(backup, tmp, StandardCopyOption.REPLACE_EXISTING);
                        moveAtomic(tmp, target);
                        rolledBack++;
                    } else if (target != null) {
                        // 没有备份说明替换还没发生或目标原本不存在；删掉半成品临时文件即可。
                        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
                    }
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    blocked.add("prepared journal 回滚失败：" + file.getFileName()
                            + "：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            blocked.add("journal 目录读取失败：" + e.getMessage());
        }
        return new Recovery(rolledBack, cleaned, List.copyOf(blocked));
    }

    /** 原子复制并返回目标 SHA-256；目标旧文件先复制到 backup。 */
    public static String copyAtomic(Path source, Path target, Path backup) {
        try {
            Files.createDirectories(target.getParent());
            if (backup != null && Files.exists(target)) {
                Files.createDirectories(backup.getParent());
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            String checksum = sha256(source);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            force(tmp);
            moveAtomic(tmp, target);
            String actual = sha256(target);
            if (!actual.equals(checksum)) {
                throw new IOException("校验和不匹配（期望 " + checksum + "，实际 " + actual + "）");
            }
            return actual;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static String sha256(Path file) {
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
        } catch (Exception e) {
            throw new RuntimeException("计算校验和失败：" + e.getMessage(), e);
        }
    }

    public static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void force(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException ignored) {
            // 不支持时退化为普通原子替换；不影响数据正确性。
        }
    }

    private static Entry read(Path file) {
        try {
            Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
            return entry;
        } catch (JsonParseException | IOException e) {
            return null;
        }
    }

    public record Recovery(int rolledBack, int cleaned, List<String> blocked) {
        public boolean clean() {
            return blocked.isEmpty() && rolledBack == 0;
        }
    }

    public static final class Entry {
        public String group;
        public String from;
        public String to;
        public String state;
        public String target;
        public String backup;
        public String checksum;
        public long time;
    }
}
