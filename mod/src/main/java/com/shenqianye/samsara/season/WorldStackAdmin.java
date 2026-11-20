package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.server.ServerBackupPaths;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 世界栈管理：导入、排序、归档，以及停止服务器后由 mod init 消费的 pending 任务。
 *
 * <p>Web 只负责校验/排队；真正搬目录发生在世界打开之前（{@link StackBootstrap#prepare}）。
 * 这样运行中的旧栈维度不会被边写边读。源目录始终只读，导入默认复制。
 */
public final class WorldStackAdmin {
    /** 旧版单任务文件；只读兼容，新任务统一写入 {@link #PENDING_IMPORTS}。 */
    public static final String PENDING_IMPORT = "pending-import.json";
    /** 导入队列：一次重启可依次执行多个导入。 */
    public static final String PENDING_IMPORTS = "pending-imports.json";
    public static final String PENDING_ARCHIVE = "pending-archive.json";
    public static final String PENDING_PROMOTE = "pending-promote.json";
    public static final String PENDING_RESTORE = "pending-stack-restore.json";
    public static final String JOBS_DIR = "world-stack-jobs";
    public static final String LAST_RESULT = "last-result.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final List<String> ROOT_EXCLUDES = List.of(
            "session.lock", "logs", "crash-reports", "backups",
            "pending-new.json", "rewind.json", "reset-pending.json",
            "pending-delete.json", "pending-teleports.json", "level.dat_old");
    static final List<String> SAMSARA_EXCLUDES = List.of(
            "stack.json", "stack", "web-map-cache", JOBS_DIR,
            PENDING_IMPORT, PENDING_IMPORTS, PENDING_ARCHIVE, PENDING_PROMOTE, PENDING_RESTORE,
            "pending-new.json");

    private WorldStackAdmin() {
    }

    /** 备份放在服务器根目录，不跟随存档；删档/复制存档不会带走或误删备份。 */
    public static Path archiveRoot(Path worldDir) {
        return serverDataRoot(worldDir).resolve("world-stack-archive");
    }

    public static Path backupRoot(Path worldDir) {
        return serverDataRoot(worldDir).resolve("world-stack-backup");
    }

    private static Path serverDataRoot(Path worldDir) {
        Path parent = worldDir.toAbsolutePath().normalize().getParent();
        return ServerBackupPaths.root(parent == null ? worldDir : parent);
    }

    static String serverRelative(Path worldDir, Path path) {
        Path parent = worldDir.toAbsolutePath().normalize().getParent();
        try {
            return (parent == null ? worldDir : parent).relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    /** 旧版本备份在 world/samsara 下；启动时搬到服务器级备份目录。 */
    private static void migrateLegacyBackups(Path worldDir) {
        moveLegacy(worldDir.resolve("samsara/stack-archive"), archiveRoot(worldDir));
        moveLegacy(worldDir.resolve("samsara/stack-backup"), backupRoot(worldDir));
    }

    private static void moveLegacy(Path from, Path to) {
        if (!Files.exists(from)) {
            return;
        }
        if (Files.exists(to)) {
            Samsara.LOGGER.warn("[世界栈] 旧备份目录 {} 仍存在，但新位置 {} 已有内容；"
                    + "未自动合并，请手动确认", from, to);
            return;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            Samsara.LOGGER.info("[世界栈] 备份已移到服务器级目录：{} → {}", from, to);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 备份迁移失败 {} → {}: {}", from, to, e.getMessage());
        }
    }

    // ---- 模型 ----

    public static Model model(Path worldDir, Path serverRoot, List<Path> importRoots) {
        Model model = new Model();
        model.worldRoot = worldDir.toAbsolutePath().normalize().toString();
        model.serverRoot = serverRoot == null ? "" : serverRoot.toAbsolutePath().normalize().toString();
        model.stackPath = StackPaths.manifest(worldDir).toString();
        for (Path root : importRoots) {
            if (root != null) {
                model.importRoots.add(root.toAbsolutePath().normalize().toString());
            }
        }
        StackManifest manifest = StackManifest.load(worldDir);
        for (StackLayer layer : manifest.layers) {
            model.stacks.add(layerView(worldDir, layer));
            model.totalBytes += model.stacks.get(model.stacks.size() - 1).dataBytes;
        }
        model.pendingImports = readImportQueue(worldDir);
        model.pendingImport = model.pendingImports.isEmpty() ? null : model.pendingImports.get(0);
        model.pendingArchive = read(worldDir, PENDING_ARCHIVE, PendingArchive.class);
        model.pendingPromote = read(worldDir, PENDING_PROMOTE, PendingPromote.class);
        model.pendingRestore = read(worldDir, PENDING_RESTORE,
                WorldStackTimeline.PendingRestore.class);
        model.restartRequired = !model.pendingImports.isEmpty() || model.pendingArchive != null
                || model.pendingPromote != null || model.pendingRestore != null;
        model.jobs = jobs(worldDir, 8);
        model.lastReport = readJobFile(worldDir.resolve("samsara").resolve(JOBS_DIR)
                .resolve(LAST_RESULT));
        model.files = fileTree(worldDir, model.stacks);
        model.backupKeep = WorldStackBackupRetention.backupKeep();
        return model;
    }

    private static LayerView layerView(Path worldDir, StackLayer layer) {
        LayerView view = new LayerView();
        view.id = layer.id;
        view.index = layer.index;
        view.top = layer.top;
        view.seed = String.valueOf(layer.seed);
        view.spawnX = layer.spawnX;
        view.spawnY = layer.spawnY;
        view.spawnZ = layer.spawnZ;
        view.createdAt = layer.createdAt;
        view.levelType = layer.levelType;
        view.source = layer.source == null || layer.source.isBlank()
                ? (layer.top ? "current" : "layer") : layer.source;
        if (layer.top && "demoted".equals(view.source)) {
            view.source = "current";
        }
        view.importedFrom = layer.importedFrom == null ? "" : layer.importedFrom;
        view.metaPath = relative(worldDir, layer.metaDir(worldDir));
        view.dimensionsPath = relative(worldDir, StackPaths.layerDimensions(worldDir, layer));
        view.loaded = layer.top || StackDimensionLoader.layer(layer.id) != null;
        for (String dim : List.of("overworld", "the_nether", "the_end")) {
            Path region = StackPaths.layerDimension(worldDir, layer, dim).resolve("region");
            DimView dv = new DimView();
            dv.id = dim;
            dv.exists = Files.isDirectory(region);
            dv.regions = dv.exists ? regionCount(region) : 0;
            view.dimensions.add(dv);
        }
        view.spawnCycles = SpawnHistory.loadFrom(layer.metaDir(worldDir)).size();
        view.dataBytes = layerBytes(worldDir, layer);
        view.importedAt = layer.importedAt;
        LevelInfo level = LevelInfo.read(layer.top ? worldDir : layer.metaDir(worldDir));
        view.saveName = level.worldName;
        view.saveCreatedAt = level.createdAt;
        view.lastPlayed = level.lastPlayed;
        view.dataVersion = level.dataVersion;
        view.versionName = level.versionName;
        view.snapshot = level.snapshot;
        return view;
    }

    private static List<FileNode> fileTree(Path worldDir, List<LayerView> stacks) {
        List<FileNode> out = new ArrayList<>();
        Path server = worldDir.toAbsolutePath().normalize().getParent();
        if (server == null) {
            server = worldDir;
        }
        addFile(out, 0, "服务器根", server, "模组配置与服务器级备份的根目录", false);
        addFile(out, 1, StoragePaths.CONFIG_DIR + "/", StoragePaths.configDirAt(server),
                "YAML 配置（不随存档）", false);
        addFile(out, 1, "backups/samsara/world-stack-archive/", archiveRoot(worldDir),
                "栈层归档备份（不随存档；用户数据，不自动删除）", true);
        addFile(out, 1, "backups/samsara/world-stack-backup/", backupRoot(worldDir),
                "导入/提升栈顶的备份（不随存档，保留最近 " + WorldStackBackupRetention.backupKeep() + " 份）", true);
        addFile(out, 1, "backups/samsara/server-backups/", server.resolve("backups/samsara/server-backups"),
                "本体存档备份（world + 服务器配置，不随存档）", false);
        addFile(out, 1, "backups/samsara/server-backup-jobs/", server.resolve("backups/samsara/server-backup-jobs"),
                "本体备份/恢复任务报告", false);
        addFile(out, 1, "backups/samsara/server-config-backups/",
                server.resolve("backups/samsara/server-config-backups"),
                "服务器配置写入前备份", false);
        addFile(out, 1, "samsara/global/（服务器级）", server.resolve("samsara/global"),
                "globalFollowWorld=false 时使用", false);
        Path legacyArchive = worldDir.resolve("samsara/stack-archive");
        if (Files.exists(legacyArchive)) {
            addFile(out, 1, "world/samsara/stack-archive/（旧位置）", legacyArchive,
                    "旧版备份；启动时会尝试迁移到服务器级目录", false);
        }
        addFile(out, 1, "world/", worldDir, "栈顶/最新存档", false);
        addFile(out, 2, "level.dat", worldDir.resolve("level.dat"), "栈顶世界元数据", false);
        addFile(out, 2, "players/", worldDir.resolve("players"), "所有栈共享的玩家数据", false);
        addFile(out, 2, "data/minecraft/", worldDir.resolve("data/minecraft"),
                "运行期共享数据；不是 claims 数据根", false);
        addFile(out, 2, "dimensions/minecraft/", worldDir.resolve("dimensions/minecraft"),
                "栈顶的主世界/下界/末地", false);
        addFile(out, 2, "dimensions/samsara/", worldDir.resolve("dimensions/samsara"),
                "旧栈层的自定义维度", false);
        for (LayerView stack : stacks) {
            if (stack.top) continue;
            addFile(out, 3, stack.id + "/",
                    worldDir.resolve(stack.dimensionsPath), "旧栈层维度", false);
        }
        Path data = worldDir.resolve("samsara");
        addFile(out, 2, "samsara/", data, "世界数据根；栈顶元数据也在这里", false);
        addFile(out, 3, "stack.json", data.resolve("stack.json"),
                "栈清单（权威）", false);
        addFile(out, 3, "spawn-history.json", data.resolve("spawn-history.json"),
                "栈顶出生点历史", false);
        addFile(out, 3, "claims/", data.resolve("claims"), "栈顶圈地", false);
        addFile(out, 3, "diff/", data.resolve("diff"), "栈顶倒流差异", false);
        addFile(out, 3, "track/", data.resolve("track"), "栈顶玩家轨迹", false);
        addFile(out, 3, "activity/", data.resolve("activity"), "静默活动记录", false);
        addFile(out, 3, "audit/", data.resolve("audit"), "审计日志", false);
        addFile(out, 3, "backups/", data.resolve("backups"), "安全删除/压缩备份", false);
        addFile(out, 3, "identity/", data.resolve("identity"), "身份运行时数据", false);
        addFile(out, 3, "global/（跟随存档）", data.resolve("global"),
                "globalFollowWorld=true 时使用", false);
        addFile(out, 3, "web-map-cache/", data.resolve("web-map-cache"),
                "地图 tile 派生缓存（可安全删除）", false);
        addFile(out, 3, "world-stack-jobs/", data.resolve(JOBS_DIR),
                "导入/归档/栈顶交换任务报告", false);
        addFile(out, 3, "stack/", data.resolve("stack"), "旧栈层元数据", false);
        for (LayerView stack : stacks) {
            if (stack.top) continue;
            addFile(out, 4, stack.id + "/", worldDir.resolve(stack.metaPath),
                    "旧栈层 level.dat / claims / diff / track / spawn-history", false);
        }
        for (String pending : List.of(PENDING_IMPORTS, PENDING_IMPORT, PENDING_ARCHIVE,
                PENDING_PROMOTE, PENDING_RESTORE)) {
            Path file = data.resolve(pending);
            if (Files.exists(file)) {
                addFile(out, 3, pending, file, "待重启执行", false);
            }
        }
        return out;
    }

    private static void addFile(List<FileNode> out, int depth, String label, Path path,
                                String note, boolean showBytes) {
        FileNode node = new FileNode();
        node.depth = depth;
        node.label = label;
        node.path = path.toAbsolutePath().normalize().toString();
        node.relative = node.path;
        node.exists = Files.exists(path);
        node.kind = Files.isDirectory(path) ? "dir" : "file";
        node.note = note;
        if (showBytes && node.exists && node.kind.equals("dir")) {
            node.bytes = sizeOf(path);
        }
        out.add(node);
    }

    static long layerBytes(Path worldDir, StackLayer layer) {
        long total = sizeOf(StackPaths.layerDimensions(worldDir, layer));
        Path meta = layer.metaDir(worldDir);
        if (layer.top) {
            total += sizeOf(worldDir.resolve("samsara").resolve("claims"));
            total += sizeOf(worldDir.resolve("samsara").resolve("diff"));
            total += sizeOf(worldDir.resolve("samsara").resolve("track"));
            total += sizeOf(worldDir.resolve("level.dat"));
        } else {
            total += sizeOf(meta);
        }
        return total;
    }

    // ---- 导入队列 ----

    /** 读取导入队列；旧版单任务文件作为队列首项兼容。 */
    static List<PendingImport> readImportQueue(Path worldDir) {
        List<PendingImport> out = new ArrayList<>();
        PendingImportQueue queue = read(worldDir, PENDING_IMPORTS, PendingImportQueue.class);
        if (queue != null && queue.imports != null) {
            for (PendingImport item : queue.imports) {
                if (item != null) {
                    out.add(item);
                }
            }
        }
        PendingImport legacy = read(worldDir, PENDING_IMPORT, PendingImport.class);
        if (legacy != null && out.stream().noneMatch(item -> sameJob(item, legacy))) {
            out.add(0, legacy);
        }
        return out;
    }

    private static boolean sameJob(PendingImport a, PendingImport b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.jobId != null && !a.jobId.isBlank() && a.jobId.equals(b.jobId)) {
            return true;
        }
        return a.source != null && !a.source.isBlank() && a.source.equals(b.source);
    }

    /** 原子写入队列；空队列时同时清理旧版单任务文件。 */
    static boolean saveImportQueue(Path worldDir, List<PendingImport> imports) {
        List<PendingImport> clean = new ArrayList<>();
        if (imports != null) {
            for (PendingImport item : imports) {
                if (item != null) {
                    clean.add(item);
                }
            }
        }
        if (clean.isEmpty()) {
            try {
                Files.deleteIfExists(stackFile(worldDir, PENDING_IMPORTS));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(stackFile(worldDir, PENDING_IMPORT));
            } catch (IOException ignored) {
            }
            return true;
        }
        PendingImportQueue queue = new PendingImportQueue();
        queue.imports.addAll(clean);
        if (!write(worldDir, PENDING_IMPORTS, queue)) {
            return false;
        }
        try {
            Files.deleteIfExists(stackFile(worldDir, PENDING_IMPORT));
        } catch (IOException ignored) {
        }
        return true;
    }

    static long estimateNeeded(long sourceBytes) {
        return sourceBytes + Math.max(64L * 1024 * 1024, sourceBytes / 20);
    }

    static long queuedNeededBytes(List<PendingImport> queue) {
        long total = 0;
        for (PendingImport item : queue) {
            total += item.neededBytes > 0 ? item.neededBytes : estimateNeeded(item.sourceBytes);
        }
        return total;
    }

    /**
     * 把队列中尚未执行的导入叠加到栈清单上，供下一项预览定位。
     *
     * <p>栈底导入每次都插到当前最下方，所以后确认的会变成新栈底；
     * between 以当时的 afterId 为准；top 每次把上一个栈顶压成新栈层。
     * 执行阶段会在真实 stack.json 上重新计算同样的位置，失败项不会让后续项错位。
     */
    static StackManifest virtualManifest(Path worldDir, List<PendingImport> queue) {
        StackManifest manifest = StackManifest.load(worldDir);
        if (queue == null || queue.isEmpty()) {
            return manifest;
        }
        for (PendingImport item : queue) {
            String position = normalizePosition(item.position);
            if ("top".equals(position)) {
                StackLayer top = manifest.top();
                if (top == null) {
                    continue;
                }
                top.top = false;
                top.id = freeId(manifest, item.demoteId);
                StackLayer newTop = new StackLayer();
                newTop.id = StackPaths.TOP_ID;
                newTop.top = true;
                newTop.source = "imported";
                manifest.layers.add(newTop);
            } else if ("between".equals(position)) {
                StackLayer after = manifest.byId(item.afterId);
                int index = after == null || after.top ? 0 : after.index + 1;
                StackLayer layer = new StackLayer();
                layer.id = freeId(manifest, item.targetId);
                layer.source = "imported";
                manifest.insertBelowTop(layer, index);
            } else {
                StackLayer layer = new StackLayer();
                layer.id = freeId(manifest, item.targetId);
                layer.source = "imported";
                manifest.insertBelowTop(layer, 0);
            }
        }
        manifest.normalize();
        return manifest;
    }

    static String freeId(StackManifest manifest, String preferred) {
        String id = preferred == null ? "" : preferred.trim();
        if (id.isEmpty() || StackPaths.TOP_ID.equals(id) || manifest.byId(id) != null) {
            return allocateId(manifest);
        }
        return id;
    }

    // ---- 导入预览 ----

    public static Preview previewImport(Path worldDir, Path serverRoot, List<Path> importRoots,
                                        String rawPath, String position, String afterId,
                                        boolean deleteSource) {
        return WorldStackImportPreview.preview(worldDir, serverRoot, importRoots, rawPath,
                position, afterId, deleteSource);
    }

    public static synchronized Map<String, Object> reorder(Path worldDir, List<String> orderedIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!readImportQueue(worldDir).isEmpty() || pending(worldDir, PENDING_ARCHIVE)
                || pending(worldDir, PENDING_PROMOTE)) {
            out.put("ok", false);
            out.put("error", "已有待执行任务；请先重启服务器完成或手动清理 pending 文件");
            return out;
        }
        try {
            StackManifest manifest = StackManifest.load(worldDir);
            List<String> ids = orderedIds == null ? List.of() : orderedIds;
            boolean includesTop = ids.contains(StackPaths.TOP_ID);
            if (!includesTop) {
                // 兼容旧版只传非栈顶列表的调用。
                manifest.reorderBelowTop(ids);
                manifest.save(worldDir);
                out.put("ok", true);
                out.put("order", ids);
                out.put("requiresRestart", false);
                return out;
            }
            java.util.Set<String> expected = new LinkedHashSet<>();
            for (StackLayer layer : manifest.layers) {
                expected.add(layer.id);
            }
            if (ids.size() != expected.size() || !new LinkedHashSet<>(ids).equals(expected)) {
                out.put("ok", false);
                out.put("error", "排序列表必须包含全部栈层且不能重复");
                return out;
            }
            if (StackPaths.TOP_ID.equals(ids.get(ids.size() - 1))) {
                List<String> belowTop = new ArrayList<>(ids);
                belowTop.remove(StackPaths.TOP_ID);
                manifest.reorderBelowTop(belowTop);
                manifest.save(worldDir);
                out.put("ok", true);
                out.put("order", ids);
                out.put("requiresRestart", false);
                return out;
            }
            // 把某个旧栈层提升为栈顶：必须交换 world 与该层的数据，重启时执行。
            String promoteId = ids.get(ids.size() - 1);
            StackLayer target = manifest.byId(promoteId);
            if (target == null || target.top) {
                out.put("ok", false);
                out.put("error", "目标栈层不存在或已经是栈顶");
                return out;
            }
            String jobId = newJobId("prom");
            PendingPromote pending = new PendingPromote();
            pending.jobId = jobId;
            pending.order = new ArrayList<>(ids);
            pending.promoteId = promoteId;
            // 真交换：原 world 接管目标层的 id/目录，目标层数据变成 world。
            pending.demoteId = promoteId;
            pending.createdAt = System.currentTimeMillis();
            if (!write(worldDir, PENDING_PROMOTE, pending)) {
                out.put("ok", false);
                out.put("error", "无法写入 pending-promote.json");
                return out;
            }
            Job job = newJob(jobId, "promote", promoteId);
            job.requiresRestart = true;
            job.message = "已排队：重启后把 " + promoteId + " 提升为栈顶，当前 world 接管 "
                    + promoteId + " 的位置";
            writeJob(worldDir, job);
            out.put("ok", true);
            out.put("order", ids);
            out.put("requiresRestart", true);
            out.put("jobId", jobId);
            out.put("job", job);
            out.put("message", job.message);
            return out;
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    public static Preview previewArchive(Path worldDir, String id) {
        return WorldStackArchive.preview(worldDir, id);
    }

    public static synchronized ScheduleResult scheduleArchive(Path worldDir, String id) {
        return WorldStackArchive.schedule(worldDir, id);
    }


    // ---- 导入排队 ----

    public static synchronized ScheduleResult scheduleImport(Path worldDir, Path serverRoot,
                                                             List<Path> importRoots, String path,
                                                             String position, String afterId,
                                                             boolean deleteSource) {
        Preview preview = previewImport(worldDir, serverRoot, importRoots, path, position,
                afterId, deleteSource);
        ScheduleResult result = new ScheduleResult();
        result.preview = preview;
        if (!preview.ok) {
            result.ok = false;
            return result;
        }
        String jobId = newJobId("imp");
        PendingImport pending = new PendingImport();
        pending.jobId = jobId;
        pending.source = preview.source.canonicalPath;
        pending.position = preview.position;
        pending.afterId = preview.afterId;
        pending.targetId = preview.targetId;
        pending.targetIndex = preview.targetIndex;
        pending.demoteId = preview.demoteId;
        pending.mode = "copy";
        pending.deleteSource = false;
        pending.createdAt = System.currentTimeMillis();
        pending.sourceBytes = preview.source.sizeBytes;
        pending.sourceFiles = preview.source.fileCount;
        pending.sourceWorldName = preview.source.worldName;
        pending.seed = preview.source.seed;
        pending.spawnX = preview.source.spawnX;
        pending.spawnY = preview.source.spawnY;
        pending.spawnZ = preview.source.spawnZ;
        pending.playerConflicts = preview.playerConflicts;
        pending.neededBytes = preview.neededBytes;
        List<PendingImport> queue = readImportQueue(worldDir);
        if (queue.stream().anyMatch(item -> sameJob(item, pending))) {
            result.ok = false;
            preview.errors.add("该源目录已在待导入队列中；请等待重启执行，或先取消队列项");
            return result;
        }
        queue.add(pending);
        if (!saveImportQueue(worldDir, queue)) {
            result.ok = false;
            preview.errors.add("无法写入 pending-imports.json");
            return result;
        }
        Job job = newJob(jobId, "import", preview.targetId);
        job.source = pending.source;
        job.requiresRestart = true;
        job.message = "已排队（第 " + queue.size() + " 项）：重启服务器后导入到 "
                + ("top".equals(pending.position) ? "栈顶 world" : preview.targetId);
        writeJob(worldDir, job);
        result.ok = true;
        result.jobId = jobId;
        result.requiresRestart = true;
        result.job = job;
        return result;
    }

    /** 取消一条尚未执行的导入；jobId 或源目录二选一。 */
    public static synchronized Map<String, Object> cancelImport(Path worldDir, String jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<PendingImport> queue = readImportQueue(worldDir);
        PendingImport removed = null;
        if (jobId != null && !jobId.isBlank()) {
            for (PendingImport item : queue) {
                if (jobId.equals(item.jobId)) {
                    removed = item;
                    break;
                }
            }
        }
        if (removed == null) {
            out.put("ok", false);
            out.put("error", "队列中没有该导入任务");
            return out;
        }
        queue.remove(removed);
        if (!saveImportQueue(worldDir, queue)) {
            out.put("ok", false);
            out.put("error", "无法写入 pending-imports.json");
            return out;
        }
        Job job = readJob(worldDir, removed.jobId);
        if (job == null) {
            job = newJob(removed.jobId, "import", removed.targetId);
        }
        job.status = "canceled";
        job.stage = "已取消";
        job.finishedAt = System.currentTimeMillis();
        job.message = "已取消排队：未执行导入 " + removed.source;
        job.log.add(job.message);
        writeJob(worldDir, job);
        out.put("ok", true);
        out.put("jobId", removed.jobId);
        out.put("removed", 1);
        out.put("remaining", queue.size());
        out.put("pendingImports", queue);
        return out;
    }

    // ---- 启动时消费 ----

    /** SERVER_STARTING 之前调用；处理 pending-import / pending-archive / pending-stack-restore。 */
    public static synchronized void consumePending(Path worldDir) {
        migrateLegacyBackups(worldDir);
        Path restoreFile = stackFile(worldDir, PENDING_RESTORE);
        Path archiveFile = stackFile(worldDir, PENDING_ARCHIVE);
        Path promoteFile = stackFile(worldDir, PENDING_PROMOTE);
        List<PendingImport> imports = readImportQueue(worldDir);
        if (Files.isRegularFile(restoreFile)) {
            WorldStackTimeline.consumePendingRestore(worldDir);
            return;
        }
        WorldStackBackupRetention.prune(worldDir, backupRoot(worldDir), WorldStackBackupRetention.backupKeep());
        int pendingCount = (imports.isEmpty() ? 0 : 1)
                + (Files.exists(archiveFile) ? 1 : 0) + (Files.exists(promoteFile) ? 1 : 0);
        if (Files.isRegularFile(worldDir.resolve("samsara/pending-new.json"))
                && pendingCount > 0) {
            Samsara.LOGGER.error("[世界栈] 同时存在 pending-new 与导入/归档/栈顶交换任务，"
                    + "拒绝自动执行；请手动清理 pending 文件");
            return;
        }
        if (pendingCount > 1) {
            Samsara.LOGGER.error("[世界栈] 同时存在多个 pending 任务，"
                    + "拒绝自动执行；请手动选择其一");
            return;
        }
        if (Files.exists(promoteFile)) {
            PendingPromote pending = read(worldDir, PENDING_PROMOTE, PendingPromote.class);
            if (pending != null) {
                WorldStackExecutor.executePromote(worldDir, pending);
            }
            return;
        }
        if (Files.exists(archiveFile)) {
            PendingArchive pending = read(worldDir, PENDING_ARCHIVE, PendingArchive.class);
            if (pending != null) {
                WorldStackExecutor.executeArchive(worldDir, pending);
            }
            return;
        }
        if (!imports.isEmpty()) {
            WorldStackExecutor.executeImportQueue(worldDir, imports);
        }
    }



    static final class LevelInfo {
        String worldName = "";
        long seed;
        int dataVersion;
        String versionName = "";
        boolean snapshot;
        int spawnX;
        int spawnY = 64;
        int spawnZ;
        String levelType = "normal";
        long createdAt;
        long lastPlayed;

        static LevelInfo read(Path source) {
            LevelInfo info = new LevelInfo();
            info.worldName = source.getFileName() == null ? "world" : source.getFileName().toString();
            try {
                CompoundTag root = NbtIo.readCompressed(source.resolve("level.dat"),
                        NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("Data");
                info.dataVersion = data.getIntOr("DataVersion", 0);
                info.lastPlayed = data.getLongOr("LastPlayed", 0L);
                info.createdAt = data.getLongOr("CreatedAt", 0L);
                if (info.createdAt <= 0) {
                    try {
                        info.createdAt = Files.readAttributes(source.resolve("level.dat"),
                                BasicFileAttributes.class).creationTime().toMillis();
                    } catch (Exception ignored) {
                        // 某些文件系统不支持创建时间；保持 0
                    }
                }
                CompoundTag version = data.getCompoundOrEmpty("Version");
                info.snapshot = version.getBooleanOr("Snapshot", false);
                info.versionName = version.getStringOr("Name", "");
                String name = data.getStringOr("LevelName", "");
                if (!name.isBlank()) {
                    info.worldName = name;
                }
                CompoundTag spawn = data.getCompoundOrEmpty("spawn");
                int[] pos = spawn.getIntArray("pos").orElse(null);
                if (pos != null && pos.length >= 3) {
                    info.spawnX = pos[0];
                    info.spawnY = pos[1];
                    info.spawnZ = pos[2];
                } else {
                    ListTag list = spawn.getListOrEmpty("pos");
                    if (list.size() >= 3) {
                        info.spawnX = list.getIntOr(0, 0);
                        info.spawnY = list.getIntOr(1, 64);
                        info.spawnZ = list.getIntOr(2, 0);
                    }
                }
                Path gen = source.resolve("data/minecraft/world_gen_settings.dat");
                if (Files.isRegularFile(gen)) {
                    CompoundTag genRoot = NbtIo.readCompressed(gen, NbtAccounter.unlimitedHeap());
                    info.seed = genRoot.getCompoundOrEmpty("data").getLongOr("seed", 0L);
                }
                if (info.seed == 0L) {
                    info.seed = data.getCompoundOrEmpty("WorldGenSettings")
                            .getLongOr("seed", 0L);
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[世界栈] 读取 level.dat 失败 {}: {}", source, e.getMessage());
            }
            return info;
        }
    }


    static void moveFailedFile(Path worldDir, String name, Path file) {
        WorldStackExecutor.moveFailed(worldDir, name, file);
    }

    static boolean writePending(Path worldDir, String name, Object value) {
        return write(worldDir, name, value);
    }

    static <T> T readPending(Path worldDir, String name, Class<T> type) {
        return read(worldDir, name, type);
    }

    static boolean serverBackupPending(Path worldDir) {
        Path server = serverRoot(worldDir);
        return Files.isRegularFile(server.resolve("backups/samsara/pending-server-backup.json"))
                || Files.isRegularFile(server.resolve("backups/samsara/pending-server-restore.json"));
    }

    static Path serverRoot(Path worldDir) {
        return WorldStackTimeline.serverRoot(worldDir);
    }

    static void writeJobQuiet(Job job) {
        // 由调用方在 finally 写盘；这里只用于进度节流占位
    }

    static void writeJob(Path worldDir, Job job) {
        Path dir = worldDir.resolve("samsara").resolve(JOBS_DIR);
        try {
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(job.id + ".json"), job);
            writeAtomic(dir.resolve(LAST_RESULT), job);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 写入任务报告失败: {}", e.getMessage());
        }
    }

    static Job readJob(Path worldDir, String id) {
        return id == null ? null : readJobFile(worldDir.resolve("samsara").resolve(JOBS_DIR)
                .resolve(id + ".json"));
    }

    public static List<Job> jobs(Path worldDir, int limit) {
        Path dir = worldDir.resolve("samsara").resolve(JOBS_DIR);
        List<Job> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals(LAST_RESULT))
                    .sorted(Comparator.comparingLong(WorldStackAdmin::mtime).reversed())
                    .limit(limit).toList();
            for (Path file : files) {
                Job job = readJobFile(file);
                if (job != null) out.add(job);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Job readJobFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Job.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static void markOperationFailed(Path worldDir, WorldStackTimeline.Operation operation,
                                            String reason) {
        if (operation == null) {
            return;
        }
        operation.restorable = false;
        operation.failReason = reason == null ? "" : reason;
        operation.notes.add("操作失败或已回滚：" + (reason == null ? "未知原因" : reason)
                + "（没有可安全撤销的完成状态）");
        WorldStackTimeline.write(worldDir, operation);
    }

    static Job newJob(String id, String type, String targetId) {
        Job job = new Job();
        job.id = id;
        job.type = type;
        job.status = "queued";
        job.stage = "排队";
        job.createdAt = System.currentTimeMillis();
        job.targetId = targetId;
        return job;
    }

    static String newJobId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static void writeAtomic(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean write(Path worldDir, String name, Object value) {
        try {
            writeAtomic(stackFile(worldDir, name), value);
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 写入 {} 失败: {}", name, e.getMessage());
            return false;
        }
    }

    private static <T> T read(Path worldDir, String name, Class<T> type) {
        Path file = stackFile(worldDir, name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean pending(Path worldDir, String name) {
        return Files.isRegularFile(stackFile(worldDir, name));
    }

    static Path stackFile(Path worldDir, String name) {
        return worldDir.resolve("samsara").resolve(name);
    }

    static long freeBytes(Path worldDir) {
        try {
            return Files.getFileStore(worldDir).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    static boolean skip(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        if (ROOT_EXCLUDES.contains(first)) {
            return true;
        }
        if ("samsara".equals(first) && relative.getNameCount() > 1) {
            String second = relative.getName(1).toString();
            return SAMSARA_EXCLUDES.contains(second);
        }
        return false;
    }

    static WalkStats walkStats(Path root) {
        WalkStats stats = new WalkStats();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skip(root.relativize(dir))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!skip(root.relativize(file))) {
                        stats.files++;
                        stats.bytes += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return stats;
    }

    static int countFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    static long sizeOf(Path root) {
        if (root == null || !Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int regionCount(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    static String human(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String relative(Path worldDir, Path path) {
        try {
            return worldDir.relativize(path.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }

    static String normalizePosition(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "", "bottom" -> "bottom";
            case "top" -> "top";
            case "between", "after" -> "between";
            default -> value;
        };
    }

    static String allocateId(StackManifest manifest) {
        Set<String> used = new LinkedHashSet<>();
        for (StackLayer layer : manifest.layers) {
            if (layer.id != null) used.add(layer.id);
        }
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            String id = StackPaths.STACK_PREFIX + i;
            if (!used.contains(id) && !StackPaths.TOP_ID.equals(id)) {
                return id;
            }
        }
        throw new IllegalStateException("无法分配栈层 id");
    }

    // ---- 数据类 ----

    public static final class Model {
        public boolean ok = true;
        public String worldRoot = "";
        public String serverRoot = "";
        public String stackPath = "";
        public List<String> importRoots = new ArrayList<>();
        public List<LayerView> stacks = new ArrayList<>();
        /** 待执行的导入队列，按确认顺序执行。 */
        public List<PendingImport> pendingImports = new ArrayList<>();
        /** 兼容旧前端：队列首项。 */
        public PendingImport pendingImport;
        public PendingArchive pendingArchive;
        public PendingPromote pendingPromote;
        public WorldStackTimeline.PendingRestore pendingRestore;
        public List<Job> jobs = new ArrayList<>();
        public Job lastReport;
        public List<FileNode> files = new ArrayList<>();
        public long totalBytes;
        public int backupKeep;
        public boolean restartRequired;
    }

    public static final class LayerView {
        public String id = "";
        public int index;
        public boolean top;
        /** 长种子以字符串返回，避免 JS Number 精度丢失。 */
        public String seed = "";
        public int spawnX;
        public int spawnY;
        public int spawnZ;
        public long createdAt;
        public long importedAt;
        public String levelType = "";
        public String source = "";
        public String importedFrom = "";
        /** level.dat 里的世界名 / 存档创建时间 / 最后游玩时间 / 数据版本。 */
        public String saveName = "";
        public long saveCreatedAt;
        public long lastPlayed;
        public int dataVersion;
        public String versionName = "";
        public boolean snapshot;
        public String metaPath = "";
        public String dimensionsPath = "";
        public long dataBytes;
        public int spawnCycles;
        public boolean loaded;
        /** 图标白名单：source/custom/mime/version/url；不含文件路径。 */
        public Map<String, Object> icon;
        public List<DimView> dimensions = new ArrayList<>();
    }

    public static final class DimView {
        public String id = "";
        public boolean exists;
        public int regions;
    }

    public static final class FileNode {
        public int depth;
        public String label = "";
        public String path = "";
        public String relative = "";
        public String kind = "dir";
        public boolean exists;
        public long bytes = -1;
        public String note = "";
    }

    public static final class SourceInfo {
        public String path = "";
        public String canonicalPath = "";
        public String layout = "";
        public String worldName = "";
        public String seed = "";
        public int spawnX;
        public int spawnY;
        public int spawnZ;
        public long sizeBytes;
        public int fileCount;
        public int playerFiles;
        public boolean sessionLocked;
        public boolean legacy;
        public boolean hasLevelDat;
        public boolean hasDimensions;
        public boolean hasGenSettings;
        public boolean hasSamsaraStack;
        public int dataVersion;
        public String versionName = "";
        public boolean snapshot;
    }

    public static final class Preview {
        public boolean ok = true;
        public Map<String, String> fields = new LinkedHashMap<>();
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> steps = new ArrayList<>();
        public SourceInfo source;
        public String position = "bottom";
        public String afterId = "";
        public String targetId = "";
        public String demoteId = "";
        public String betweenUpper = "";
        public String backupPath = "";
        public int targetIndex;
        public long freeBytes;
        public long neededBytes;
        public int playerConflicts;
        public List<String> conflictFiles = new ArrayList<>();
        public boolean requiresRestart = true;
        public String mode = "copy";
    }

    public static final class PendingImport {
        public String jobId = "";
        public String source = "";
        public String position = "bottom";
        public String afterId = "";
        public String targetId = "";
        public int targetIndex;
        public String demoteId = "";
        public String mode = "copy";
        public boolean deleteSource;
        public long createdAt;
        public long sourceBytes;
        public int sourceFiles;
        public String sourceWorldName = "";
        public String seed = "";
        public int spawnX;
        public int spawnY;
        public int spawnZ;
        public int playerConflicts;
        /** 预览时估算的磁盘占用，用于给后续队列项预留空间。 */
        public long neededBytes;
    }

    /** {@code pending-imports.json} 的外层结构；数组本身也兼容读取。 */
    public static final class PendingImportQueue {
        public int schemaVersion = 1;
        public List<PendingImport> imports = new ArrayList<>();
    }

    public static final class PendingArchive {
        public String jobId = "";
        public String id = "";
        public long createdAt;
        public String archivePath = "";
    }

    public static final class PendingPromote {
        public String jobId = "";
        /** 包含 world 的完整目标顺序（栈底 → 栈顶）。 */
        public List<String> order = new ArrayList<>();
        public String promoteId = "";
        public String demoteId = "";
        public long createdAt;
    }

    public static final class Job {
        public String id = "";
        public String type = "";
        public String status = "queued";
        public String stage = "";
        public int progress;
        public String message = "";
        public long createdAt;
        public long startedAt;
        public long finishedAt;
        public boolean requiresRestart;
        public String source = "";
        public String targetId = "";
        public String operation = "";
        public List<String> affectedIds = new ArrayList<>();
        public StackManifest beforeManifest = new StackManifest();
        public StackManifest afterManifest = new StackManifest();
        public List<String> log = new ArrayList<>();
    }

    public static final class ScheduleResult {
        public boolean ok;
        public String jobId = "";
        public boolean requiresRestart = true;
        public Preview preview;
        public Job job;
    }

    static final class WalkStats {
        long bytes;
        int files;
    }
}








