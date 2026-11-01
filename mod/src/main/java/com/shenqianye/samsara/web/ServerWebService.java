package com.shenqianye.samsara.web;

import com.shenqianye.samsara.server.ServerBackup;
import com.shenqianye.samsara.server.ServerConfigFiles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Web「本体」页：服务器配置 + 存档备份。 */
public class ServerWebService {
    private final MinecraftServer server;
    private final Path serverRoot;

    public ServerWebService(MinecraftServer server) {
        this.server = server;
        this.serverRoot = ServerBackup.serverRoot();
    }

    public ServerWebService(MinecraftServer server, Path serverRoot) {
        this.server = server;
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
    }

    public Path serverRoot() {
        return serverRoot;
    }

    public Map<String, Object> model() {
        Map<String, Object> out = new LinkedHashMap<>(ServerBackup.model(serverRoot));
        Path world = worldDir();
        out.put("worldDir", world.toString());
        out.put("worldExists", Files.isDirectory(world));
        return out;
    }

    public Map<String, Object> saveConfig(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = str(body, "id");
        String content = str(body, "content");
        ServerConfigFiles.SaveResult result = ServerConfigFiles.save(serverRoot, id, content, server);
        out.put("ok", result.ok);
        out.put("error", result.error);
        out.put("restartRequired", result.restartRequired);
        out.put("applied", result.applied);
        out.put("message", result.message);
        out.put("backupPath", result.backupPath);
        if (result.ok) {
            out.put("configFiles", ServerConfigFiles.readAll(serverRoot));
        }
        return out;
    }

    public Map<String, Object> previewCreate() {
        return ServerBackup.previewCreate(serverRoot);
    }

    public Map<String, Object> createBackup(Map<String, Object> body) {
        if (!bool(body, "confirm")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "创建备份需要确认");
            return out;
        }
        return ServerBackup.scheduleCreate(serverRoot);
    }

    public Map<String, Object> previewRestore(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        ServerBackup.Preview preview = ServerBackup.previewRestore(serverRoot, str(body, "id"));
        out.put("ok", preview.ok);
        out.put("preview", preview);
        if (!preview.errors.isEmpty()) {
            out.put("error", String.join("；", preview.errors));
        }
        return out;
    }

    public Map<String, Object> restore(Map<String, Object> body) {
        if (!bool(body, "confirm")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "恢复需要二次确认");
            return out;
        }
        return ServerBackup.scheduleRestore(serverRoot, str(body, "id"));
    }

    public Map<String, Object> deleteBackup(Map<String, Object> body) {
        return ServerBackup.delete(serverRoot, str(body, "id"), bool(body, "confirm"));
    }

    public Map<String, Object> jobs() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobs", ServerBackup.jobs(serverRoot, 20));
        out.put("lastReport", ServerBackup.job(serverRoot, "last-result"));
        return out;
    }

    public Path backupFile(String id) {
        Path file = ServerBackup.backupFile(serverRoot, id == null ? "" : id.trim());
        return Files.isRegularFile(file) ? file : null;
    }

    private Path worldDir() {
        if (server != null) {
            try {
                return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
            }
        }
        return ServerBackup.worldDir(serverRoot);
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
