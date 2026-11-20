package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.UUID;

import static com.shenqianye.samsara.season.WorldStackAdmin.*;

/**
 * 世界栈 pending 任务的执行阶段：重启时消费导入/归档/栈顶交换队列。
 * 预览与排队在 {@link WorldStackImportPreview} / {@link WorldStackArchive}，这里只做落盘与回滚。
 */
final class WorldStackExecutor {

    private WorldStackExecutor() {
    }

    /** 依次执行导入队列；单项失败时保留剩余项到下次重启，避免位置连锁错误。 */
    static void executeImportQueue(Path worldDir, List<PendingImport> queue) {
        int total = queue.size();
        for (int i = 0; i < queue.size(); ) {
            PendingImport pending = queue.get(i);
            boolean ok = executeImport(worldDir, pending);
            queue.remove(i);
            saveImportQueue(worldDir, queue);
            if (!ok) {
                write(worldDir, "pending-import.failed-" + pending.jobId + ".json", pending);
                Samsara.LOGGER.warn("[世界栈] 导入队列第 {}/{} 项失败；剩余 {} 项保留到下次重启",
                        i + 1, total, queue.size());
                return;
            }
        }
        Samsara.LOGGER.info("[世界栈] 导入队列执行完成：{} 项", total);
    }

    static void executeArchive(Path worldDir, PendingArchive pending) {
        Job job = readJob(worldDir, pending.jobId);
        if (job == null) {
            job = newJob(pending.jobId, "archive", pending.id);
        }
        Path file = stackFile(worldDir, PENDING_ARCHIVE);
        Path archive = null;
        Path dims = null;
        Path meta = null;
        boolean movedDims = false;
        boolean movedMeta = false;
        WorldStackTimeline.Operation operation = null;
        try {
            job.status = "running";
            job.stage = "校验栈层";
            job.startedAt = System.currentTimeMillis();
            writeJob(worldDir, job);
            StackManifest manifest = StackManifest.load(worldDir);
            StackLayer layer = manifest.byId(pending.id);
            if (layer == null || layer.top) {
                throw new IOException("栈层不存在或为栈顶：" + pending.id);
            }
            archive = Path.of(pending.archivePath);
            Files.createDirectories(archive);
            operation = WorldStackTimeline.beginOperation(worldDir, "archive", pending.jobId,
                    archive, manifest, List.of(layer.id));
            dims = StackPaths.layerDimensions(worldDir, layer);
            meta = layer.metaDir(worldDir);
            moveIfExists(dims, archive.resolve("dimensions"));
            movedDims = true;
            moveIfExists(meta, archive.resolve("meta"));
            movedMeta = true;
            manifest.removeBelowTop(layer.id);
            manifest.save(worldDir);
            if (StackManifest.load(worldDir).byId(layer.id) != null) {
                throw new IOException("stack.json 写入后仍存在该层");
            }
            WorldStackTimeline.finishOperation(worldDir, archive, operation,
                    StackManifest.load(worldDir), true,
                    List.of("归档来源保留在 " + archive + "；撤销时从该目录复制回层"),
                    null);
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.finishedAt = System.currentTimeMillis();
            job.message = "已归档 " + layer.id + " -> " + archive;
            job.log.add("备份：" + archive);
            Files.deleteIfExists(file);
            writeJob(worldDir, job);
            Samsara.LOGGER.info("[世界栈] {}", job.message);
        } catch (Exception e) {
            try {
                if (movedMeta && archive != null && meta != null) {
                    moveIfExists(archive.resolve("meta"), meta);
                }
                if (movedDims && archive != null && dims != null) {
                    moveIfExists(archive.resolve("dimensions"), dims);
                }
                job.log.add("已回滚归档目录移动，原栈层保持可用");
            } catch (Exception rollback) {
                job.log.add("归档回滚失败：" + rollback.getMessage());
            }
            markOperationFailed(worldDir, operation, e.getMessage());
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "归档失败：" + e.getMessage();
            job.log.add(job.message);
            writeJob(worldDir, job);
            moveFailed(worldDir, PENDING_ARCHIVE, file);
            Samsara.LOGGER.error("[世界栈] {}（原栈层未删除）", job.message);
        }
    }

    static void executePromote(Path worldDir, PendingPromote pending) {
        Job job = readJob(worldDir, pending.jobId);
        if (job == null) {
            job = newJob(pending.jobId, "promote", pending.promoteId);
        }
        Path file = stackFile(worldDir, PENDING_PROMOTE);
        Path backup = backupRoot(worldDir).resolve("promote-" + pending.jobId);
        Path manifestBackup = backupManifest(worldDir);
        Path topDims = worldDir.resolve("dimensions/minecraft");
        Path topLevel = worldDir.resolve("level.dat");
        Path topGen = worldDir.resolve("data/minecraft/world_gen_settings.dat");
        Path topDataRoot = worldDir.resolve("samsara");
        Path topIcon = worldDir.resolve("icon.png");
        Path targetDims = null;
        Path targetMeta = null;
        Path tmpDims = null;
        Path tmpMeta = null;
        boolean targetToTemp = false;
        boolean topToTarget = false;
        boolean targetToTop = false;
        boolean targetGenMoved = false;
        WorldStackTimeline.Operation operation = null;
        try {
            job.status = "running";
            job.stage = "校验排序";
            job.startedAt = System.currentTimeMillis();
            writeJob(worldDir, job);
            StackManifest manifest = StackManifest.load(worldDir);
            StackLayer currentTop = manifest.top();
            String promoteId = pending.promoteId;
            StackLayer target = manifest.byId(promoteId);
            if (currentTop == null || target == null || target.top) {
                throw new IOException("栈顶交换目标无效");
            }
            java.util.Set<String> expected = new LinkedHashSet<>();
            for (StackLayer layer : manifest.layers) {
                expected.add(layer.id);
            }
            if (pending.order == null || pending.order.size() != expected.size()
                    || !new LinkedHashSet<>(pending.order).equals(expected)
                    || !promoteId.equals(pending.order.get(pending.order.size() - 1))) {
                throw new IOException("排序列表与当前栈层不一致；请重新预览排序");
            }
            Files.createDirectories(backup);
            operation = WorldStackTimeline.beginOperation(worldDir, "promote", pending.jobId,
                    backup, manifest, List.of(StackPaths.TOP_ID, promoteId));
            job.stage = "保留操作前快照";
            WorldStackTimeline.snapshotTop(worldDir, backup);
            WorldStackTimeline.snapshotLayer(worldDir, backup, promoteId);
            targetDims = StackPaths.layerDimensions(worldDir, target);
            targetMeta = target.metaDir(worldDir);
            tmpDims = backup.resolve("swap/target-dimensions");
            tmpMeta = backup.resolve("swap/target-meta");
            if (!Files.isDirectory(targetDims) || !Files.isRegularFile(targetMeta.resolve("level.dat"))) {
                throw new IOException("目标栈层缺少 dimensions 或 level.dat：" + promoteId);
            }
            Files.createDirectories(backup.resolve("swap"));
            copyFile(topLevel, backup.resolve("top-level.dat"));
            copyFile(topGen, backup.resolve("top-world_gen_settings.dat"));
            copyFile(topIcon, backup.resolve("top-icon.png"));
            job.stage = "交换目标层与当前 world";
            job.progress = 10;
            writeJob(worldDir, job);

            // 1) 目标层数据移到临时目录
            moveIfExists(targetDims, tmpDims);
            moveIfExists(targetMeta, tmpMeta);
            targetToTemp = true;

            // 2) 当前 world 数据移到目标层 id/目录
            Files.createDirectories(targetMeta.resolve("data/minecraft"));
            moveIfExists(topDims, targetDims);
            moveIfExists(topLevel, targetMeta.resolve("level.dat"));
            copyFile(topGen, targetMeta.resolve("data/minecraft/world_gen_settings.dat"));
            moveIfExists(topGen, backup.resolve("top-world_gen_settings.moved.dat"));
            moveIfExists(topIcon, targetMeta.resolve("icon.png"));
            for (String name : List.of("claims", "diff", "track")) {
                moveIfExists(topDataRoot.resolve(name), targetMeta.resolve(name));
            }
            moveIfExists(topDataRoot.resolve(SpawnHistory.FILE_NAME),
                    targetMeta.resolve(SpawnHistory.FILE_NAME));
            topToTarget = true;

            // 3) 目标层数据移到 world 栈顶
            job.stage = "提升目标层为栈顶";
            job.progress = 55;
            writeJob(worldDir, job);
            moveIfExists(tmpDims, topDims);
            moveIfExists(tmpMeta.resolve("level.dat"), topLevel);
            moveIfExists(tmpMeta.resolve("icon.png"), topIcon);
            if (Files.isRegularFile(tmpMeta.resolve("data/minecraft/world_gen_settings.dat"))) {
                moveIfExists(tmpMeta.resolve("data/minecraft/world_gen_settings.dat"), topGen);
                targetGenMoved = true;
            } else {
                copyFile(backup.resolve("top-world_gen_settings.dat"), topGen);
            }
            for (String name : List.of("claims", "diff", "track")) {
                moveIfExists(tmpMeta.resolve(name), topDataRoot.resolve(name));
            }
            moveIfExists(tmpMeta.resolve(SpawnHistory.FILE_NAME),
                    topDataRoot.resolve(SpawnHistory.FILE_NAME));
            moveIfExists(tmpMeta.resolve("data/minecraft"),
                    backup.resolve("target-data-minecraft"));
            targetToTop = true;

            job.stage = "写入栈清单";
            job.progress = 90;
            writeJob(worldDir, job);
            currentTop.top = false;
            currentTop.id = promoteId;
            if (currentTop.source == null || currentTop.source.isBlank()
                    || "current".equals(currentTop.source)) {
                currentTop.source = "demoted";
            }
            StackLayer newTop = target.copy();
            newTop.id = StackPaths.TOP_ID;
            newTop.top = true;
            if (newTop.source == null || newTop.source.isBlank()
                    || "demoted".equals(newTop.source)) {
                newTop.source = "current";
            }
            List<StackLayer> layers = new ArrayList<>();
            for (String id : pending.order) {
                if (StackPaths.TOP_ID.equals(id)) {
                    layers.add(currentTop);
                } else if (!promoteId.equals(id)) {
                    StackLayer layer = manifest.byId(id);
                    if (layer == null) {
                        throw new IOException("排序列表包含未知栈层：" + id);
                    }
                    layers.add(layer);
                }
            }
            layers.add(newTop);
            manifest.layers = layers;
            manifest.normalize();
            manifest.save(worldDir);
            StackManifest verify = StackManifest.load(worldDir);
            if (verify.top() == null || !StackPaths.TOP_ID.equals(verify.top().id)
                    || verify.byId(promoteId) == null
                    || verify.byId(promoteId).top) {
                throw new IOException("stack.json 写入后校验失败");
            }
            WorldStackTimeline.finishOperation(worldDir, backup, operation, verify, true,
                    List.of("栈顶交换：原 world 与 " + promoteId + " 的层位互换",
                            "备份包含操作前栈顶与目标层的完整快照"),
                    null);
            ensureSpawn(topDataRoot, LevelInfo.read(worldDir));
            deleteRecursive(tmpDims);
            deleteRecursive(tmpMeta);
            if (manifestBackup != null) {
                Files.deleteIfExists(manifestBackup);
            }
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.finishedAt = System.currentTimeMillis();
            job.message = "已把 " + promoteId + " 提升为栈顶；原 world 接管 " + promoteId + " 的层位";
            job.log.add("备份：" + backup);
            Files.deleteIfExists(file);
            writeJob(worldDir, job);
            Samsara.LOGGER.info("[世界栈] {}", job.message);
        } catch (Exception e) {
            try {
                if (targetToTop) {
                    moveIfExists(topDims, tmpDims);
                    moveIfExists(topLevel, tmpMeta.resolve("level.dat"));
                    moveIfExists(topIcon, tmpMeta.resolve("icon.png"));
                    if (targetGenMoved && Files.isRegularFile(topGen)) {
                        moveIfExists(topGen, tmpMeta.resolve("data/minecraft/world_gen_settings.dat"));
                    } else {
                        Files.deleteIfExists(topGen);
                    }
                    for (String name : List.of("claims", "diff", "track")) {
                        moveIfExists(topDataRoot.resolve(name), tmpMeta.resolve(name));
                    }
                    moveIfExists(topDataRoot.resolve(SpawnHistory.FILE_NAME),
                            tmpMeta.resolve(SpawnHistory.FILE_NAME));
                }
                if (topToTarget) {
                    moveIfExists(targetDims, topDims);
                    moveIfExists(targetMeta.resolve("level.dat"), topLevel);
                    moveIfExists(targetMeta.resolve("icon.png"), topIcon);
                    Files.deleteIfExists(topGen);
                    copyFile(backup.resolve("top-world_gen_settings.dat"), topGen);
                    for (String name : List.of("claims", "diff", "track")) {
                        moveIfExists(targetMeta.resolve(name), topDataRoot.resolve(name));
                    }
                    moveIfExists(targetMeta.resolve(SpawnHistory.FILE_NAME),
                            topDataRoot.resolve(SpawnHistory.FILE_NAME));
                }
                if (targetToTemp) {
                    deleteRecursive(targetMeta);
                    moveIfExists(tmpMeta, targetMeta);
                    moveIfExists(tmpDims, targetDims);
                }
                deleteRecursive(tmpDims);
                deleteRecursive(tmpMeta);
                restoreManifest(worldDir, manifestBackup);
                job.log.add("已回滚栈顶交换，当前 world 与目标栈层保持原状");
            } catch (Exception rollback) {
                job.log.add("栈顶交换回滚失败：" + rollback.getMessage());
            }
            markOperationFailed(worldDir, operation, e.getMessage());
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "栈顶交换失败：" + e.getMessage();
            job.log.add(job.message);
            writeJob(worldDir, job);
            moveFailed(worldDir, PENDING_PROMOTE, file);
            Samsara.LOGGER.error("[世界栈] {}", job.message);
        }
    }

    static boolean executeImport(Path worldDir, PendingImport pending) {
        Job job = readJob(worldDir, pending.jobId);
        if (job == null) {
            job = newJob(pending.jobId, "import", pending.targetId);
        }
        try {
            job.status = "running";
            job.stage = "校验源目录";
            job.startedAt = System.currentTimeMillis();
            writeJob(worldDir, job);
            Path source = Path.of(pending.source);
            if (!Files.isDirectory(source)) {
                throw new IOException("源目录不存在：" + source);
            }
            if (!Files.isRegularFile(source.resolve("level.dat"))
                    || !Files.isDirectory(source.resolve("dimensions/minecraft"))) {
                throw new IOException("源目录不是 26.3 新布局（缺少 level.dat 或 dimensions/minecraft）");
            }
            if (!Files.isRegularFile(source.resolve("data/minecraft/world_gen_settings.dat"))) {
                throw new IOException("源目录缺少 data/minecraft/world_gen_settings.dat；"
                        + "导入会导致世界无法启动，已中止（原栈与源目录保持不变）");
            }
            int serverDataVersion = LevelInfo.read(worldDir).dataVersion;
            int sourceDataVersion = LevelInfo.read(source).dataVersion;
            if (serverDataVersion > 0 && sourceDataVersion > serverDataVersion) {
                throw new IOException("源世界版本高于服务器（DataVersion " + sourceDataVersion
                        + " > " + serverDataVersion + "）；服务器会拒绝打开，已中止"
                        + "（原栈与源目录保持不变）");
            }
            LevelInfo sourceLevel = LevelInfo.read(source);
            if (sourceLevel.snapshot && !LevelInfo.read(worldDir).snapshot) {
                throw new IOException("源世界由快照版本创建（"
                        + (sourceLevel.versionName.isBlank() ? "snapshot" : sourceLevel.versionName)
                        + "）；正式版服务器会拒绝打开，已中止（原栈与源目录保持不变）");
            }
            if (WorldStackImportGuard.sessionLocked(source.resolve("session.lock"))) {
                throw new IOException("源目录 session.lock 仍被占用");
            }
            if ("top".equals(pending.position)) {
                importTop(worldDir, source, pending, job);
            } else {
                importLayer(worldDir, source, pending, job);
            }
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.finishedAt = System.currentTimeMillis();
            job.message = "导入完成：" + pending.sourceWorldName + " -> "
                    + ("top".equals(pending.position) ? "world" : job.targetId)
                    + "（源目录未修改）";
            job.log.add(job.message);
            writeJob(worldDir, job);
            Samsara.LOGGER.info("[世界栈] {}", job.message);
            return true;
        } catch (Exception e) {
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "导入失败：" + e.getMessage();
            job.log.add(job.message);
            writeJob(worldDir, job);
            Samsara.LOGGER.error("[世界栈] {}（原栈与源目录保持可用）", job.message);
            return false;
        }
    }

    static void importLayer(Path worldDir, Path source, PendingImport pending, Job job)
            throws IOException {
        StackManifest manifest = StackManifest.load(worldDir);
        String id = freeId(manifest, pending.targetId);
        int insertIndex = 0;
        if ("between".equals(normalizePosition(pending.position))) {
            StackLayer after = manifest.byId(pending.afterId);
            if (after == null || after.top) {
                throw new IOException("插入位置已不存在或现在是栈顶：" + pending.afterId);
            }
            insertIndex = after.index + 1;
        }
        Path finalDims = worldDir.resolve("dimensions/samsara").resolve(id);
        Path finalMeta = worldDir.resolve("samsara/stack").resolve(id);
        if (Files.exists(finalDims) || Files.exists(finalMeta)) {
            throw new IOException("目标栈层目录已存在：" + id);
        }
        Path stagingDims = worldDir.resolve("dimensions/samsara").resolve(id + ".importing");
        Path stagingMeta = worldDir.resolve("samsara/stack").resolve(id + ".importing");
        deleteRecursive(stagingDims);
        deleteRecursive(stagingMeta);
        Path manifestBackup = backupManifest(worldDir);
        List<Path> addedPlayers = new ArrayList<>();
        Path backup = backupRoot(worldDir).resolve("import-" + pending.jobId);
        Files.createDirectories(backup);
        WorldStackTimeline.Operation operation = WorldStackTimeline.beginOperation(worldDir,
                "import", pending.jobId, backup, manifest, List.of(id));
        try {
            job.stage = "复制维度";
            writeJob(worldDir, job);
            copyTree(source.resolve("dimensions/minecraft"), stagingDims, job, 5, 55);
            job.stage = "复制元数据";
            writeJob(worldDir, job);
            copyFile(source.resolve("level.dat"), stagingMeta.resolve("level.dat"));
            // 导入存档自带 icon.png：非默认灰白图会作为栈层卡片回退图，不复制目录其余截图。
            copyFile(source.resolve("icon.png"), stagingMeta.resolve("icon.png"));
            copyTree(source.resolve("data/minecraft"), stagingMeta.resolve("data/minecraft"),
                    job, 55, 65);
            for (String name : List.of("claims", "diff", "track")) {
                copyTree(source.resolve("samsara").resolve(name),
                        stagingMeta.resolve(name), job, 65, 75);
            }
            copyFile(source.resolve("samsara/spawn-history.json"),
                    stagingMeta.resolve(SpawnHistory.FILE_NAME));
            job.stage = "合并玩家数据";
            job.progress = 80;
            writeJob(worldDir, job);
            addedPlayers = mergePlayers(worldDir, source, job);
            job.stage = "写入栈层";
            job.progress = 88;
            writeJob(worldDir, job);
            Files.createDirectories(finalDims.getParent());
            Files.createDirectories(finalMeta.getParent());
            moveIfExists(stagingDims, finalDims);
            moveIfExists(stagingMeta, finalMeta);
            LevelInfo level = LevelInfo.read(source);
            StackLayer layer = new LayerInfo(pending, level, id).toLayer();
            manifest.insertBelowTop(layer, insertIndex);
            manifest.save(worldDir);
            if (StackManifest.load(worldDir).byId(id) == null) {
                throw new IOException("stack.json 写入失败：新层未落盘");
            }
            ensureSpawn(finalMeta, level);
            WorldStackTimeline.finishOperation(worldDir, backup, operation,
                    StackManifest.load(worldDir), true,
                    List.of("导入源目录保持只读：" + pending.source,
                            "撤销时移除本次新增的 " + id + " 层与新增玩家文件"),
                    addedPlayers);
            if (manifestBackup != null) {
                Files.deleteIfExists(manifestBackup);
            }
            job.progress = 99;
            job.targetId = id;
            job.log.add("新栈层 " + id + " index=" + insertIndex + "，源 " + source);
        } catch (Exception e) {
            deleteRecursive(finalDims);
            deleteRecursive(finalMeta);
            deleteRecursive(stagingDims);
            deleteRecursive(stagingMeta);
            deleteAddedPlayers(addedPlayers);
            restoreManifest(worldDir, manifestBackup);
            markOperationFailed(worldDir, operation, e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    static void importTop(Path worldDir, Path source, PendingImport pending, Job job)
            throws IOException {
        StackManifest manifest = StackManifest.load(worldDir);
        StackLayer currentTop = manifest.top();
        if (currentTop == null) {
            throw new IOException("当前栈没有栈顶 world");
        }
        String demoteId = pending.demoteId == null || pending.demoteId.isBlank()
                ? allocateId(manifest) : pending.demoteId;
        if (manifest.byId(demoteId) != null) {
            demoteId = allocateId(manifest);
        }
        Path dimsMinecraft = worldDir.resolve("dimensions/minecraft");
        Path oldDims = worldDir.resolve("dimensions/samsara").resolve(demoteId);
        Path oldMeta = worldDir.resolve("samsara/stack").resolve(demoteId);
        Path backup = backupRoot(worldDir).resolve("import-" + pending.jobId);
        if (Files.exists(oldDims) || Files.exists(oldMeta)) {
            throw new IOException("旧栈目标目录已存在：" + demoteId);
        }
        Path manifestBackup = backupManifest(worldDir);
        List<Path> addedPlayers = new ArrayList<>();
        Path importDims = worldDir.resolve("dimensions/minecraft.importing");
        deleteRecursive(importDims);
        Files.createDirectories(backup);
        WorldStackTimeline.Operation operation = WorldStackTimeline.beginOperation(worldDir,
                "import", pending.jobId, backup, manifest,
                List.of(StackPaths.TOP_ID, demoteId));
        job.stage = "保留操作前顶层快照";
        WorldStackTimeline.snapshotTop(worldDir, backup);
        boolean movedOldDims = false;
        boolean movedLevel = false;
        boolean movedData = false;
        boolean movedTopData = false;
        try {
            Files.createDirectories(backup);
            Files.createDirectories(oldDims.getParent());
            Files.createDirectories(oldMeta.getParent());
            job.stage = "备份并压入旧栈顶";
            job.progress = 5;
            writeJob(worldDir, job);
            // 当前栈顶 dimensions -> 旧栈层
            moveIfExists(dimsMinecraft, oldDims);
            movedOldDims = true;
            copyFile(worldDir.resolve("level.dat"), oldMeta.resolve("level.dat"));
            // 栈顶被压入旧栈时，world/icon.png 属于旧栈层；先备份再复制到旧栈元数据目录。
            copyFile(worldDir.resolve("icon.png"), oldMeta.resolve("icon.png"));
            copyFile(worldDir.resolve("icon.png"), backup.resolve("icon.png"));
            copyFile(worldDir.resolve("data/minecraft/world_gen_settings.dat"),
                    oldMeta.resolve("data/minecraft/world_gen_settings.dat"));
            movedTopData = true;
            for (String name : List.of("claims", "diff", "track")) {
                moveIfExists(worldDir.resolve("samsara").resolve(name), oldMeta.resolve(name));
            }
            moveIfExists(worldDir.resolve("samsara/spawn-history.json"),
                    oldMeta.resolve(SpawnHistory.FILE_NAME));
            // 当前 world 顶层文件备份后清空
            moveIfExists(worldDir.resolve("level.dat"), backup.resolve("level.dat"));
            movedLevel = true;
            moveIfExists(worldDir.resolve("data/minecraft"), backup.resolve("data-minecraft"));
            movedData = true;
            job.stage = "复制新 world";
            job.progress = 25;
            writeJob(worldDir, job);
            copyTree(source.resolve("dimensions/minecraft"), importDims, job, 25, 60);
            job.stage = "复制 level.dat / data";
            job.progress = 62;
            writeJob(worldDir, job);
            copyFile(source.resolve("level.dat"), worldDir.resolve("level.dat"));
            // 新栈顶的 icon.png 跟随存档；源没有图标时不能继续沿用旧栈顶的图。
            Files.deleteIfExists(worldDir.resolve("icon.png"));
            copyFile(source.resolve("icon.png"), worldDir.resolve("icon.png"));
            copyTree(source.resolve("data/minecraft"), worldDir.resolve("data/minecraft"),
                    job, 62, 78);
            if (!Files.isRegularFile(worldDir.resolve("level.dat"))
                    || !Files.isRegularFile(
                            worldDir.resolve("data/minecraft/world_gen_settings.dat"))) {
                throw new IOException("复制后缺少 level.dat 或 world_gen_settings.dat，"
                        + "世界将无法启动；已回滚");
            }
            for (String name : List.of("claims", "diff", "track")) {
                copyTree(source.resolve("samsara").resolve(name),
                        worldDir.resolve("samsara").resolve(name), job, 78, 80);
            }
            copyFile(source.resolve("samsara/spawn-history.json"),
                    worldDir.resolve("samsara/spawn-history.json"));
            addPlayers(mergedPlayers(worldDir, source, job, addedPlayers), job);
            job.stage = "写入栈清单";
            job.progress = 92;
            writeJob(worldDir, job);
            moveIfExists(importDims, dimsMinecraft);
            LevelInfo level = LevelInfo.read(source);
            currentTop.top = false;
            currentTop.id = demoteId;
            currentTop.source = currentTop.source == null || currentTop.source.isBlank()
                    ? "demoted" : currentTop.source;
            StackLayer newTop = new LayerInfo(pending, level, StackPaths.TOP_ID).toLayer();
            newTop.top = true;
            newTop.id = StackPaths.TOP_ID;
            List<StackLayer> layers = new ArrayList<>();
            for (StackLayer l : manifest.layers) {
                if (l != currentTop && !l.top) {
                    layers.add(l);
                }
            }
            layers.add(currentTop);
            layers.add(newTop);
            manifest.layers = layers;
            manifest.normalize();
            manifest.save(worldDir);
            WorldStackTimeline.finishOperation(worldDir, backup, operation,
                    StackManifest.load(worldDir), true,
                    List.of("导入源目录保持只读：" + pending.source,
                            "操作前顶层完整快照在 " + backup.resolve("before/top")),
                    addedPlayers);
            ensureSpawn(worldDir.resolve("samsara"), level);
            Files.deleteIfExists(backup.resolve("level.dat"));
            if (manifestBackup != null) {
                Files.deleteIfExists(manifestBackup);
            }
            job.progress = 99;
            job.targetId = StackPaths.TOP_ID;
        } catch (Exception e) {
            job.log.add("回滚：" + e.getMessage());
            deleteRecursive(dimsMinecraft);
            deleteRecursive(importDims);
            if (movedOldDims) {
                moveIfExists(oldDims, dimsMinecraft);
            }
            deleteRecursive(oldMeta);
            deleteRecursive(worldDir.resolve("level.dat"));
            if (movedLevel) {
                moveIfExists(backup.resolve("level.dat"), worldDir.resolve("level.dat"));
            }
            copyFile(backup.resolve("icon.png"), worldDir.resolve("icon.png"));
            deleteRecursive(worldDir.resolve("data/minecraft"));
            if (movedData) {
                moveIfExists(backup.resolve("data-minecraft"), worldDir.resolve("data/minecraft"));
            }
            if (movedTopData) {
                for (String name : List.of("claims", "diff", "track")) {
                    moveIfExists(oldMeta.resolve(name), worldDir.resolve("samsara").resolve(name));
                }
                moveIfExists(oldMeta.resolve(SpawnHistory.FILE_NAME),
                        worldDir.resolve("samsara").resolve(SpawnHistory.FILE_NAME));
            }
            deleteAddedPlayers(addedPlayers);
            restoreManifest(worldDir, manifestBackup);
            markOperationFailed(worldDir, operation, e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    // ---- 文件工具 ----

    private static final class LayerInfo {
        final PendingImport pending;
        final LevelInfo level;
        final String id;

        LayerInfo(PendingImport pending, LevelInfo level, String id) {
            this.pending = pending;
            this.level = level;
            this.id = id;
        }

        StackLayer toLayer() {
            StackLayer layer = new StackLayer();
            layer.schemaVersion = StackManifest.CURRENT_VERSION;
            layer.id = id;
            layer.top = StackPaths.TOP_ID.equals(id);
            layer.worldId = UUID.randomUUID().toString();
            layer.seed = level.seed;
            layer.levelType = level.levelType;
            layer.createdAt = System.currentTimeMillis();
            layer.spawnX = level.spawnX;
            layer.spawnY = level.spawnY;
            layer.spawnZ = level.spawnZ;
            layer.source = "imported";
            layer.importedFrom = pending.source;
            layer.importedAt = System.currentTimeMillis();
            return layer;
        }
    }

    private static void ensureSpawn(Path metaDir, LevelInfo level) {
        if (Files.isRegularFile(metaDir.resolve(SpawnHistory.FILE_NAME))) {
            return;
        }
        SpawnHistory history = new SpawnHistory();
        history.ensureInitial(level.spawnX, level.spawnZ, System.currentTimeMillis());
        history.saveTo(metaDir);
    }

    private static void copyTree(Path source, Path target, Job job, int from, int to)
            throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        long total = Math.max(1, sizeOf(source));
        long[] copied = {0};
        long[] lastUpdate = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path rel = source.relativize(dir);
                if (skip(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path rel = source.relativize(file);
                if (skip(rel)) {
                    return FileVisitResult.CONTINUE;
                }
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                copied[0] += Files.size(file);
                long now = System.currentTimeMillis();
                if (now - lastUpdate[0] > 1500) {
                    lastUpdate[0] = now;
                    job.progress = from + (int) Math.min(to - from,
                            (to - from) * copied[0] / total);
                    job.stage = "复制 " + rel.toString().replace('\\', '/');
                    job.log.add(job.stage);
                    writeJobQuiet(job);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<Path> mergePlayers(Path worldDir, Path source, Job job) throws IOException {
        return mergedPlayers(worldDir, source, job, new ArrayList<>());
    }

    private static List<Path> mergedPlayers(Path worldDir, Path source, Job job,
                                            List<Path> added) throws IOException {
        Path src = source.resolve("players/data");
        Path dst = worldDir.resolve("players/data");
        if (!Files.isDirectory(src)) {
            return added;
        }
        Files.createDirectories(dst);
        List<Path> files;
        try (Stream<Path> stream = Files.walk(src)) {
            files = stream.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path rel = src.relativize(file);
            Path target = dst.resolve(rel);
            if (Files.exists(target)) {
                job.log.add("玩家数据冲突，保留现有：" + rel.toString().replace('\\', '/'));
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            added.add(target);
        }
        return added;
    }

    private static void addPlayers(List<Path> paths, Job job) {
        if (!paths.isEmpty()) {
            job.log.add("合并玩家数据 " + paths.size() + " 个文件");
        }
    }

    private static void deleteAddedPlayers(List<Path> added) {
        for (Path path : added) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    private static void moveIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyFile(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(child);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Path backupManifest(Path worldDir) {
        Path file = StackPaths.manifest(worldDir);
        Path backup = file.resolveSibling("stack.json.import-backup");
        try {
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                return backup;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static void restoreManifest(Path worldDir, Path backup) {
        if (backup == null) {
            return;
        }
        try {
            if (Files.isRegularFile(backup)) {
                Files.copy(backup, StackPaths.manifest(worldDir),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
            }
        } catch (IOException ignored) {
        }
    }

    static void moveFailed(Path worldDir, String name, Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return;
            }
            Files.move(file, file.resolveSibling(name + ".failed-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
