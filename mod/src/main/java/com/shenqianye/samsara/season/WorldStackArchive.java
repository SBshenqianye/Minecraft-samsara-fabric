package com.shenqianye.samsara.season;

import java.nio.file.Files;
import java.nio.file.Path;

/** 栈层归档：预览与排队（真正执行仍在重启阶段）。 */
public final class WorldStackArchive {

    private WorldStackArchive() {
    }

    public static WorldStackAdmin.Preview preview(Path worldDir, String id) {
        WorldStackAdmin.Preview p = new WorldStackAdmin.Preview();
        p.position = "archive";
        p.targetId = id == null ? "" : id.trim();
        StackManifest manifest = StackManifest.load(worldDir);
        StackLayer layer = manifest.byId(p.targetId);
        if (layer == null) {
            p.fields.put("id", "栈层不存在");
            p.errors.add("栈层不存在：" + p.targetId);
        } else if (layer.top) {
            p.fields.put("id", "不允许归档栈顶 world");
            p.errors.add("不允许归档栈顶 world；请先导入新栈顶或使用 reset new");
        }
        if (!WorldStackAdmin.readImportQueue(worldDir).isEmpty()
                || WorldStackAdmin.pending(worldDir, WorldStackAdmin.PENDING_ARCHIVE)
                || WorldStackAdmin.pending(worldDir, WorldStackAdmin.PENDING_PROMOTE)) {
            p.errors.add("已有待执行任务；请先重启服务器完成或手动清理 pending 文件");
        }
        if (Files.isRegularFile(worldDir.resolve("samsara/pending-new.json"))) {
            p.errors.add("已有 pending-new.json；请先重启完成 reset new，再做归档");
        }
        if (layer != null && !layer.top) {
            p.source = new WorldStackAdmin.SourceInfo();
            p.source.path = WorldStackAdmin.relative(worldDir, layer.metaDir(worldDir));
            p.source.canonicalPath = layer.metaDir(worldDir).toString();
            p.source.layout = "stack-layer";
            p.source.sizeBytes = WorldStackAdmin.layerBytes(worldDir, layer);
            p.freeBytes = WorldStackAdmin.freeBytes(worldDir);
            p.neededBytes = p.source.sizeBytes;
            p.requiresRestart = true;
            p.backupPath = WorldStackAdmin.serverRelative(worldDir, WorldStackAdmin.archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>";
            p.steps.add("移动 " + WorldStackAdmin.serverRelative(worldDir,
                            StackPaths.layerDimensions(worldDir, layer))
                    + " -> " + WorldStackAdmin.serverRelative(worldDir, WorldStackAdmin.archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>/dimensions");
            p.steps.add("移动 " + WorldStackAdmin.serverRelative(worldDir, layer.metaDir(worldDir))
                    + " -> " + WorldStackAdmin.serverRelative(worldDir, WorldStackAdmin.archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>/meta");
            p.steps.add("从 stack.json 移除该层并重排 index；world 保持栈顶");
            p.warnings.add("归档会保留完整备份，不直接删除；重启时执行");
            p.warnings.add("该层的 claims/diff/track 随 meta 一起归档，不再参与运行");
        }
        p.ok = p.errors.isEmpty();
        return p;
    }

    public static synchronized WorldStackAdmin.ScheduleResult schedule(Path worldDir, String id) {
        WorldStackAdmin.Preview preview = preview(worldDir, id);
        WorldStackAdmin.ScheduleResult result = new WorldStackAdmin.ScheduleResult();
        result.preview = preview;
        if (!preview.ok) {
            result.ok = false;
            return result;
        }
        String jobId = WorldStackAdmin.newJobId("arc");
        WorldStackAdmin.PendingArchive pending = new WorldStackAdmin.PendingArchive();
        pending.jobId = jobId;
        pending.id = preview.targetId;
        pending.createdAt = System.currentTimeMillis();
        pending.archivePath = WorldStackAdmin.archiveRoot(worldDir)
                .resolve(pending.id + "-" + jobId).toString();
        if (!WorldStackAdmin.write(worldDir, WorldStackAdmin.PENDING_ARCHIVE, pending)) {
            result.ok = false;
            preview.errors.add("无法写入 pending-archive.json");
            return result;
        }
        WorldStackAdmin.Job job = WorldStackAdmin.newJob(jobId, "archive", preview.targetId);
        job.source = preview.source == null ? "" : preview.source.canonicalPath;
        job.requiresRestart = true;
        job.message = "已排队：重启服务器后归档 " + pending.id;
        WorldStackAdmin.writeJob(worldDir, job);
        result.ok = true;
        result.jobId = jobId;
        result.requiresRestart = true;
        result.job = job;
        return result;
    }
}
