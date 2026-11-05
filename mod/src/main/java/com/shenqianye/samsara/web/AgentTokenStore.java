package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.config.spec.ConfigFileIO;
import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent token 存储：{@code config/samsara/agent-tokens.json}。
 *
 * <p>只保存 token 的 SHA-256，明文只在创建时返回一次。主管理员 token 仍来自
 * {@code web.yml}，不在此文件中。
 */
public final class AgentTokenStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private volatile List<TokenRecord> cache;

    private AgentTokenStore() {
        this(defaultFile());
    }

    AgentTokenStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static AgentTokenStore getInstance() {
        return Holder.INSTANCE;
    }

    private static Path defaultFile() {
        try {
            Path root = StoragePaths.configRoot();
            if (root != null) {
                return root.resolve("agent-tokens.json");
            }
        } catch (Throwable ignored) {
        }
        return Path.of("config", "samsara", "agent-tokens.json").toAbsolutePath().normalize();
    }

    private static final class Holder {
        private static final AgentTokenStore INSTANCE = new AgentTokenStore();
    }

    public Path file() {
        return file;
    }

    public synchronized List<TokenRecord> tokens() {
        if (cache == null) {
            cache = load();
        }
        return cache;
    }

    public synchronized Map<String, Object> create(String name, List<String> scopes) {
        String id = name == null ? "" : name.trim();
        if (id.isBlank()) {
            return Map.of("ok", false, "error", "name 不能为空");
        }
        if (id.equalsIgnoreCase("admin")) {
            return Map.of("ok", false, "error", "name 不能使用 admin");
        }
        List<TokenRecord> list = new ArrayList<>(tokens());
        if (list.stream().anyMatch(record -> record.name.equalsIgnoreCase(id))) {
            return Map.of("ok", false, "error", "token 名称已存在：" + id);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String scope : scopes == null ? List.<String>of() : scopes) {
            AgentScope parsed = AgentScope.from(scope);
            if (parsed == null) {
                return Map.of("ok", false, "error", "未知 scope：" + scope);
            }
            wanted.add(parsed.id());
        }
        if (wanted.isEmpty()) {
            wanted.add(AgentScope.READ.id());
        }
        String token = "sk_" + randomHex(32);
        TokenRecord record = new TokenRecord();
        record.name = id;
        record.tokenHash = sha256(token);
        record.scopes = List.copyOf(wanted);
        record.enabled = true;
        record.createdAt = System.currentTimeMillis();
        list.add(record);
        save(list);
        cache = List.copyOf(list);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", record.name);
        out.put("token", token);
        out.put("scopes", record.scopes);
        out.put("message", "token 只显示一次，请立即保存");
        return out;
    }

    public synchronized Map<String, Object> revoke(String name) {
        String id = name == null ? "" : name.trim();
        List<TokenRecord> list = new ArrayList<>(tokens());
        boolean removed = list.removeIf(record -> record.name.equalsIgnoreCase(id));
        if (!removed) {
            return Map.of("ok", false, "error", "token 不存在：" + id);
        }
        save(list);
        cache = List.copyOf(list);
        return Map.of("ok", true, "name", id, "message", "已撤销");
    }

    public List<Map<String, Object>> listPublic() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TokenRecord record : tokens()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", record.name);
            row.put("scopes", record.scopes);
            row.put("enabled", record.enabled);
            row.put("createdAt", record.createdAt);
            out.add(row);
        }
        return out;
    }

    public TokenRecord findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = sha256(token);
        for (TokenRecord record : tokens()) {
            if (record.enabled && constantTimeEquals(record.tokenHash, hash)) {
                return record;
            }
        }
        return null;
    }

    private List<TokenRecord> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Data data = GSON.fromJson(ConfigFileIO.read(file), Data.class);
            if (data == null || data.tokens == null) {
                return List.of();
            }
            List<TokenRecord> out = new ArrayList<>();
            for (TokenRecord record : data.tokens) {
                if (record != null && record.name != null && record.tokenHash != null) {
                    out.add(record);
                }
            }
            return List.copyOf(out);
        } catch (RuntimeException | IOException e) {
            return List.of();
        }
    }

    private void save(List<TokenRecord> list) {
        Data data = new Data();
        data.tokens = list;
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ConfigFileIO.atomicWrite(file, GSON.toJson(data) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("保存 agent token 失败：" + e.getMessage(), e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static final class TokenRecord {
        public String name = "";
        public String tokenHash = "";
        public List<String> scopes = List.of();
        public boolean enabled = true;
        public long createdAt;

        public Set<AgentScope> scopeSet() {
            Set<AgentScope> out = new LinkedHashSet<>();
            for (String scope : scopes == null ? List.<String>of() : scopes) {
                AgentScope parsed = AgentScope.from(scope);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            return out;
        }
    }

    private static final class Data {
        List<TokenRecord> tokens = List.of();
    }
}
