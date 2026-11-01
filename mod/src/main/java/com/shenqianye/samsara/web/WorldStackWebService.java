package com.shenqianye.samsara.web;

import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.season.WorldStackAdmin;
import com.shenqianye.samsara.storage.StoragePaths;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web「世界栈」页：列出栈层、导入预览/排队、排序、归档。
 *
 * <p>导入/归档只写 pending 文件，由 {@link WorldStackAdmin#consumePending} 在下次启动、
 * 世界打开之前执行；排序是立即写 stack.json，只改 index/parent，不改目录名。
 */
public class WorldStackWebService {
    public Map<String, Object> model() {
        WorldStackAdmin.Model model = WorldStackAdmin.model(worldRoot(), serverRoot(), importRoots());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("worldRoot", model.worldRoot);
        out.put("serverRoot", model.serverRoot);
        out.put("stackPath", model.stackPath);
        out.put("importRoots", model.importRoots);
        out.put("stacks", model.stacks);
        out.put("pendingImport", model.pendingImport);
        out.put("pendingArchive", model.pendingArchive);
        out.put("jobs", model.jobs);
        out.put("lastReport", model.lastReport);
        out.put("totalBytes", model.totalBytes);
        out.put("restartRequired", model.restartRequired);
        return out;
    }

    public Map<String, Object> previewImport(Map<String, Object> body) {
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(worldRoot(), serverRoot(),
                importRoots(), string(body, "path"), string(body, "position"),
                string(body, "afterId"), bool(body, "deleteSource"));
        return preview(preview);
    }

    public Map<String, Object> importWorld(Map<String, Object> body) {
        WorldStackAdmin.ScheduleResult result = WorldStackAdmin.scheduleImport(worldRoot(),
                serverRoot(), importRoots(), string(body, "path"), string(body, "position"),
                string(body, "afterId"), bool(body, "deleteSource"));
        Map<String, Object> out = preview(result.preview);
        out.put("ok", result.ok);
        out.put("jobId", result.jobId);
        out.put("requiresRestart", result.requiresRestart);
        out.put("job", result.job);
        return out;
    }

    public Map<String, Object> reorder(Map<String, Object> body) {
        List<String> ids = stringList(body.get("ids"));
        Map<String, Object> result = WorldStackAdmin.reorder(worldRoot(), ids);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            Map<String, Object> model = model();
            result.put("stacks", model.get("stacks"));
        }
        return result;
    }

    public Map<String, Object> previewArchive(Map<String, Object> body) {
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewArchive(worldRoot(),
                string(body, "id"));
        return preview(preview);
    }

    public Map<String, Object> archive(Map<String, Object> body) {
        WorldStackAdmin.ScheduleResult result = WorldStackAdmin.scheduleArchive(worldRoot(),
                string(body, "id"));
        Map<String, Object> out = preview(result.preview);
        out.put("ok", result.ok);
        out.put("jobId", result.jobId);
        out.put("requiresRestart", result.requiresRestart);
        out.put("job", result.job);
        return out;
    }

    public Map<String, Object> jobs() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobs", WorldStackAdmin.jobs(worldRoot(), 20));
        return out;
    }

    public Map<String, Object> job(String id) {
        WorldStackAdmin.Model model = WorldStackAdmin.model(worldRoot(), serverRoot(), importRoots());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (model.lastReport != null && id != null && id.equals(model.lastReport.id)) {
            out.put("job", model.lastReport);
            return out;
        }
        for (WorldStackAdmin.Job job : model.jobs) {
            if (id != null && id.equals(job.id)) {
                out.put("job", job);
                return out;
            }
        }
        out.put("ok", false);
        out.put("error", "任务不存在");
        return out;
    }

    private static Map<String, Object> preview(WorldStackAdmin.Preview preview) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", preview.ok);
        out.put("fields", preview.fields);
        out.put("errors", preview.errors);
        out.put("warnings", preview.warnings);
        out.put("steps", preview.steps);
        out.put("source", preview.source);
        out.put("position", preview.position);
        out.put("afterId", preview.afterId);
        out.put("targetId", preview.targetId);
        out.put("demoteId", preview.demoteId);
        out.put("targetIndex", preview.targetIndex);
        out.put("freeBytes", preview.freeBytes);
        out.put("neededBytes", preview.neededBytes);
        out.put("playerConflicts", preview.playerConflicts);
        out.put("conflictFiles", preview.conflictFiles);
        out.put("requiresRestart", preview.requiresRestart);
        out.put("mode", preview.mode);
        return out;
    }

    private static Path worldRoot() {
        return StoragePaths.get().worldRoot();
    }

    private static Path serverRoot() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    }

    private static List<Path> importRoots() {
        List<Path> out = new ArrayList<>();
        try {
            for (String raw : ConfigManager.getInstance().stringList(ConfigSchema.WEB, "importRoots")) {
                if (raw != null && !raw.isBlank()) {
                    out.add(Path.of(raw.trim()).toAbsolutePath().normalize());
                }
            }
        } catch (RuntimeException ignored) {
            // 配置尚未初始化时只允许服务器根目录
        }
        return out;
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean b) return b;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
