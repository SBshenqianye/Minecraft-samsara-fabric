package com.shenqianye.samsara.opauth;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OP / owner 登录凭据存储。
 *
 * <p>只保存 PBKDF2 哈希与一次性注册码哈希，不保存明文密码。
 * 文件固定为 {@code config/samsara/op-auth.yml}，写入使用临时文件 + 原子替换。
 */
public final class OpAuthStore {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private boolean enabled = true;
    private int timeoutSeconds = 120;
    private int codeMinutes = 30;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public OpAuthStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized boolean enabled() {
        return enabled;
    }

    public synchronized int timeoutSeconds() {
        return timeoutSeconds;
    }

    public synchronized int codeMinutes() {
        return codeMinutes;
    }

    public synchronized Entry get(String name) {
        return name == null ? null : entries.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object rootObject = yaml.load(reader);
            if (!(rootObject instanceof Map<?, ?> root)) {
                return;
            }
            enabled = bool(root.get("enabled"), true);
            timeoutSeconds = Math.max(15, integer(root.get("timeoutSeconds"), 120));
            codeMinutes = Math.max(1, integer(root.get("codeMinutes"), 30));
            entries.clear();
            Object rawEntries = root.get("entries");
            if (rawEntries instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    if (!(item.getValue() instanceof Map<?, ?> data)) {
                        continue;
                    }
                    Entry entry = Entry.from(data);
                    if (!entry.name.isBlank()) {
                        entries.put(entry.name.toLowerCase(Locale.ROOT), entry);
                    }
                }
            }
        } catch (Exception ignored) {
            // 损坏时保留空表，下一次保存会覆盖成合法结构
            entries.clear();
        }
    }

    public synchronized void save() {
        StringBuilder out = new StringBuilder();
        out.append("# op-auth.yml · samsara OP / owner 登录\n");
        out.append("# 只保存 PBKDF2 哈希与一次性注册码哈希；不保存明文密码。\n");
        out.append("# 受保护名单来自 ops.json 与 permissions.yml 的 owners。\n\n");
        out.append("enabled: ").append(enabled).append('\n');
        out.append("timeoutSeconds: ").append(timeoutSeconds).append('\n');
        out.append("codeMinutes: ").append(codeMinutes).append('\n');
        if (entries.isEmpty()) {
            out.append("entries: {}\n");
        } else {
            out.append("entries:\n");
            for (Entry entry : entries.values()) {
                out.append("  ").append(scalar(entry.name)).append(":\n");
                out.append("    name: ").append(scalar(entry.name)).append('\n');
                out.append("    salt: ").append(scalar(entry.salt)).append('\n');
                out.append("    hash: ").append(scalar(entry.hash)).append('\n');
                out.append("    iterations: ").append(entry.iterations).append('\n');
                out.append("    codeHash: ").append(scalar(entry.codeHash)).append('\n');
                out.append("    codeExpires: ").append(entry.codeExpires).append('\n');
                out.append("    codeAttempts: ").append(entry.codeAttempts).append('\n');
            }
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 保存失败不阻塞游戏；调用方会记录日志
        }
    }

    /** 设置密码；同时清空一次性注册码。 */
    public synchronized void setPassword(String name, String password) {
        Entry entry = entry(name);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        entry.salt = Base64.getEncoder().encodeToString(salt);
        entry.hash = Base64.getEncoder().encodeToString(hash(password, salt, ITERATIONS));
        entry.iterations = ITERATIONS;
        entry.codeHash = "";
        entry.codeExpires = 0;
        entry.codeAttempts = 0;
        save();
    }

    public synchronized boolean verifyPassword(String name, String password) {
        Entry entry = get(name);
        if (entry == null || entry.salt.isBlank() || entry.hash.isBlank()) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(entry.salt);
            byte[] expected = Base64.getDecoder().decode(entry.hash);
            byte[] actual = hash(password, salt, entry.iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 生成一次性注册码，返回明文（只在这一刻出现）。 */
    public synchronized String generateCode(String name) {
        Entry entry = entry(name);
        String code = randomCode();
        entry.codeHash = hashToken(code);
        entry.codeExpires = System.currentTimeMillis() + codeMinutes * 60_000L;
        entry.codeAttempts = 0;
        save();
        return code;
    }

    /** 清除密码并生成新的一次性注册码。 */
    public synchronized String reset(String name) {
        Entry entry = entry(name);
        entry.salt = "";
        entry.hash = "";
        entry.iterations = ITERATIONS;
        return generateCode(entry.name);
    }

    /**
     * 校验并消费一次性注册码。
     * 失败会累加尝试次数；超过 5 次直接作废，需要管理员重新生成。
     */
    public synchronized CodeResult consumeCode(String name, String code) {
        Entry entry = get(name);
        if (entry == null || entry.codeHash.isBlank()) {
            return CodeResult.NO_CODE;
        }
        if (entry.codeExpires > 0 && System.currentTimeMillis() > entry.codeExpires) {
            entry.codeHash = "";
            entry.codeExpires = 0;
            save();
            return CodeResult.EXPIRED;
        }
        if (entry.codeAttempts >= 5) {
            entry.codeHash = "";
            entry.codeExpires = 0;
            save();
            return CodeResult.TOO_MANY;
        }
        if (code == null || !MessageDigest.isEqual(
                entry.codeHash.getBytes(StandardCharsets.UTF_8),
                hashToken(code).getBytes(StandardCharsets.UTF_8))) {
            entry.codeAttempts++;
            if (entry.codeAttempts >= 5) {
                entry.codeHash = "";
                entry.codeExpires = 0;
                save();
                return CodeResult.TOO_MANY;
            }
            save();
            return CodeResult.WRONG;
        }
        entry.codeHash = "";
        entry.codeExpires = 0;
        entry.codeAttempts = 0;
        save();
        return CodeResult.OK;
    }

    public synchronized void remove(String name) {
        if (name != null) {
            entries.remove(name.toLowerCase(Locale.ROOT));
            save();
        }
    }

    private Entry entry(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return entries.computeIfAbsent(key, ignored -> new Entry(name));
    }

    private static byte[] hash(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    private static String hashToken(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String randomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static String scalar(String value) {
        String text = value == null ? "" : value;
        if (text.isEmpty() || text.equalsIgnoreCase("null")
                || text.matches(".*[:#\\-\\[\\]{},&*!|>'\"%@`].*")
                || text.startsWith(" ") || text.endsWith(" ")) {
            return "'" + text.replace("'", "''") + "'";
        }
        return text;
    }

    public enum CodeResult {
        OK, NO_CODE, EXPIRED, WRONG, TOO_MANY
    }

    public static final class Entry {
        public String name;
        public String salt = "";
        public String hash = "";
        public int iterations = ITERATIONS;
        public String codeHash = "";
        public long codeExpires;
        public int codeAttempts;

        Entry(String name) {
            this.name = name == null ? "" : name;
        }

        static Entry from(Map<?, ?> data) {
            Entry entry = new Entry(text(data.get("name")));
            entry.salt = text(data.get("salt"));
            entry.hash = text(data.get("hash"));
            entry.iterations = Math.max(1_000, integer(data.get("iterations"), ITERATIONS));
            entry.codeHash = text(data.get("codeHash"));
            entry.codeExpires = data.get("codeExpires") instanceof Number n ? n.longValue() : 0;
            entry.codeAttempts = integer(data.get("codeAttempts"), 0);
            return entry;
        }

        public boolean hasPassword() {
            return !salt.isBlank() && !hash.isBlank();
        }

        public boolean hasCode() {
            return !codeHash.isBlank() && (codeExpires == 0 || codeExpires > System.currentTimeMillis());
        }

        private static String text(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }
}
