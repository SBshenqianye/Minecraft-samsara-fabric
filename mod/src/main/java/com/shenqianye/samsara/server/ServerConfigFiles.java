package com.shenqianye.samsara.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器本体配置文件（server.properties / 白名单 / OP / 封禁 / eula）的读写。
 *
 * <p>只允许固定文件；写入前备份到 {@code backups/samsara/server-config-backups/}，原子替换。
 * JSON 文件只校验“是数组”，不猜测字段；非法内容直接拒绝，不写盘。
 */
public final class ServerConfigFiles {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public enum Kind {
        PROPERTIES, JSON_ARRAY, EULA, TEXT
    }

    public record Spec(String id, String fileName, Kind kind, boolean restartRequired,
                       String description) {
    }

    public static final List<Spec> SPECS = List.of(
            new Spec("server-properties", "server.properties", Kind.PROPERTIES, true,
                    "端口、level-name、游戏规则、RCON 等；保存后需重启生效"),
            new Spec("whitelist", "whitelist.json", Kind.JSON_ARRAY, false,
                    "白名单；保存后立即重新载入（需先开启 white-list）"),
            new Spec("ops", "ops.json", Kind.JSON_ARRAY, false,
                    "OP 名单；保存后立即重新载入"),
            new Spec("banned-players", "banned-players.json", Kind.JSON_ARRAY, false,
                    "玩家封禁列表；保存后立即重新载入"),
            new Spec("banned-ips", "banned-ips.json", Kind.JSON_ARRAY, false,
                    "IP 封禁列表；保存后立即重新载入"),
            new Spec("eula", "eula.txt", Kind.EULA, true,
                    "EULA 同意标记；保存后需重启生效")
    );

    private ServerConfigFiles() {
    }

    public static Spec spec(String id) {
        for (Spec spec : SPECS) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        return null;
    }

    public static Path path(Path serverRoot, String id) {
        Spec spec = spec(id);
        return spec == null ? null : serverRoot.resolve(spec.fileName());
    }

    public static List<Map<String, Object>> readAll(Path serverRoot) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Spec spec : SPECS) {
            Path file = serverRoot.resolve(spec.fileName());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", spec.id());
            row.put("name", spec.fileName());
            row.put("kind", spec.kind().name().toLowerCase());
            row.put("description", spec.description());
            row.put("restartRequired", spec.restartRequired());
            boolean exists = Files.isRegularFile(file);
            row.put("exists", exists);
            try {
                row.put("bytes", exists ? Files.size(file) : 0L);
                row.put("modified", exists ? Files.getLastModifiedTime(file).toMillis() : 0L);
                row.put("content", exists ? Files.readString(file, StandardCharsets.UTF_8) : "");
                row.put("error", "");
            } catch (IOException e) {
                row.put("bytes", 0L);
                row.put("modified", 0L);
                row.put("content", "");
                row.put("error", e.getMessage());
            }
            out.add(row);
        }
        return out;
    }

    public static final class SaveResult {
        public boolean ok;
        public String error = "";
        public boolean restartRequired;
        public boolean applied;
        public String message = "";
        public String backupPath = "";
    }

    public static SaveResult save(Path serverRoot, String id, String content,
                                  MinecraftServer server) {
        SaveResult result = new SaveResult();
        Spec spec = spec(id);
        if (spec == null) {
            result.error = "未知配置文件：" + id;
            return result;
        }
        String text = content == null ? "" : content;
        if (text.length() > MAX_BYTES) {
            result.error = "文件超过 2MB，已拒绝保存";
            return result;
        }
        String normalized = normalize(spec, text, result);
        if (!result.error.isEmpty()) {
            return result;
        }
        Path file = serverRoot.resolve(spec.fileName());
        try {
            if (Files.isRegularFile(file)) {
                Path backup = ServerBackupPaths.root(serverRoot).resolve("server-config-backups")
                        .resolve(LocalDateTime.now().format(STAMP))
                        .resolve(spec.fileName());
                Files.createDirectories(backup.getParent());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                result.backupPath = backup.toString();
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, normalized, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            result.error = "写入失败：" + e.getMessage();
            return result;
        }
        result.ok = true;
        result.restartRequired = spec.restartRequired();
        if (!spec.restartRequired()) {
            result.applied = applyLive(server, spec, result);
        }
        result.message = spec.restartRequired()
                ? "已写入；需要重启服务器生效"
                : result.applied ? "已写入并立即生效" : "已写入；重启后生效";
        return result;
    }

    private static String normalize(Spec spec, String text, SaveResult result) {
        String body = text.replace("\r\n", "\n").replace('\r', '\n');
        switch (spec.kind()) {
            case PROPERTIES -> {
                int lineNo = 0;
                boolean hasKey = false;
                for (String line : body.split("\n", -1)) {
                    lineNo++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        result.error = "server.properties 第 " + lineNo
                                + " 行不是 key=value：" + trimmed;
                        return text;
                    }
                    hasKey = true;
                }
                if (!hasKey) {
                    result.error = "server.properties 不能为空";
                    return text;
                }
                return body.endsWith("\n") ? body : body + "\n";
            }
            case JSON_ARRAY -> {
                String trimmed = body.trim();
                if (trimmed.isEmpty()) {
                    trimmed = "[]";
                }
                try {
                    JsonElement parsed = JsonParser.parseString(trimmed);
                    if (!parsed.isJsonArray()) {
                        result.error = spec.fileName() + " 必须是 JSON 数组";
                        return text;
                    }
                } catch (RuntimeException e) {
                    result.error = spec.fileName() + " JSON 解析失败：" + e.getMessage();
                    return text;
                }
                return trimmed.endsWith("\n") ? trimmed : trimmed + "\n";
            }
            case EULA -> {
                boolean found = false;
                for (String line : body.split("\n", -1)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("eula=")) {
                        String value = trimmed.substring("eula=".length()).trim();
                        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                            result.error = "eula 只能是 true 或 false";
                            return text;
                        }
                        found = true;
                    }
                }
                if (!found) {
                    result.error = "eula.txt 缺少 eula=true/false";
                    return text;
                }
                return body.endsWith("\n") ? body : body + "\n";
            }
            default -> {
                return body;
            }
        }
    }

    private static boolean applyLive(MinecraftServer server, Spec spec, SaveResult result) {
        if (server == null) {
            return false;
        }
        try {
            PlayerList players = server.getPlayerList();
            if (players == null) {
                return false;
            }
            switch (spec.id()) {
                case "whitelist" -> players.reloadWhiteList();
                case "ops" -> players.getOps().load();
                case "banned-players" -> players.getBans().load();
                case "banned-ips" -> players.getIpBans().load();
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            result.message = "文件已写入，但立即重载失败：" + e.getMessage();
            return false;
        }
    }
}
