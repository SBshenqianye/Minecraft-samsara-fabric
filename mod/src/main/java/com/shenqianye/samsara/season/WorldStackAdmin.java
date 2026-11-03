package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.server.ServerBackupPaths;
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
    private static final List<String> ROOT_EXCLUDES = List.of(
            "session.lock", "logs", "crash-reports", "backups",
            "pending-new.json", "rewind.json", "reset-pending.json",
            "pending-delete.json", "pending-teleports.json", "level.dat_old");
    private static final List<String> SAMSARA_EXCLUDES = List.of(
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

    private static String serverRelative(Path worldDir, Path path) {
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

    /** 保留份数；读不到配置时回退默认值。 */
    public static int backupKeep() {
        try {
            return Math.max(0, ConfigManager.getInstance()
                    .integer(ConfigSchema.WEB, "backupKeep"));
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /**
     * 只清理服务器级 {@code world-stack-backup}（导入/提升栈顶的临时回滚备份）。
     * {@code world-stack-archive} 是用户数据，永不自动删除。
     *
     * @return 实际删除的目录数
     */
    public static int pruneBackups(Path worldDir, int keep) {
        if (keep <= 0) {
            return 0;
        }
        Path root = backupRoot(worldDir);
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    // pre-restore 是撤销前保留的当前状态，属于用户安全网，不参与自动清理。
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.startsWith("pre-restore-") && !name.startsWith("restore-");
                    })
                    .forEach(dirs::add);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 读取备份目录失败 {}: {}", root, e.getMessage());
            return 0;
        }
        if (dirs.size() <= keep) {
            return 0;
        }
        dirs.sort(Comparator.comparingLong(WorldStackAdmin::lastModified).reversed());
        int removed = 0;
        for (int i = keep; i < dirs.size(); i++) {
            Path old = dirs.get(i);
            if (protectedBackup(worldDir, old)) {
                continue;
            }
            deleteRecursive(old);
            if (!Files.exists(old)) {
                removed++;
            }
        }
        if (removed > 0) {
            Samsara.LOGGER.info("[世界栈] 备份清理（保留最新 {} 份）: 删除 {} 个旧目录",
                    keep, removed);
        }
        return removed;
    }

    /**
     * pre-restore 是撤销/恢复的安全网，永不自动删除；时间线末端操作快照也保留，
     * 否则用户刚排队撤销、重启前恰好触发保留策略时会丢失撤销依据。
     */
    private static boolean protectedBackup(Path worldDir, Path dir) {
        String name = dir.getFileName().toString();
        if (name.startsWith(WorldStackTimeline.PRE_RESTORE_PREFIX)
                || name.startsWith(WorldStackTimeline.RESTORE_STAGING_PREFIX)) {
            return true;
        }
        Path operation = dir.resolve(WorldStackTimeline.FILE_NAME);
        if (!Files.isRegularFile(operation)) {
            return false;
        }
        WorldStackTimeline.Operation op = WorldStackTimeline.readOperation(worldDir, operation);
        return op != null && op.restorable && op.restoredAt == 0
                && WorldStackTimeline.headId(worldDir).equals(op.id);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
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
        model.backupKeep = backupKeep();
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
        addFile(out, 1, "config/samsara/", server.resolve("config/samsara"),
                "YAML 配置（不随存档）", false);
        addFile(out, 1, "backups/samsara/world-stack-archive/", archiveRoot(worldDir),
                "栈层归档备份（不随存档；用户数据，不自动删除）", true);
        addFile(out, 1, "backups/samsara/world-stack-backup/", backupRoot(worldDir),
                "导入/提升栈顶的备份（不随存档，保留最近 " + backupKeep() + " 份）", true);
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

    private static long layerBytes(Path worldDir, StackLayer layer) {
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
    private static boolean saveImportQueue(Path worldDir, List<PendingImport> imports) {
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

    private static long estimateNeeded(long sourceBytes) {
        return sourceBytes + Math.max(64L * 1024 * 1024, sourceBytes / 20);
    }

    private static long queuedNeededBytes(List<PendingImport> queue) {
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
    private static StackManifest virtualManifest(Path worldDir, List<PendingImport> queue) {
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

    private static String freeId(StackManifest manifest, String preferred) {
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
        Preview p = new Preview();
        p.position = normalizePosition(position);
        p.afterId = afterId == null ? "" : afterId.trim();
        p.mode = "copy";
        List<PendingImport> queued = readImportQueue(worldDir);
        SourceInfo source = inspectSource(worldDir, serverRoot, importRoots, rawPath, p);
        if (source == null) {
            p.ok = false;
            return p;
        }
        p.source = source;
        if (deleteSource) {
            p.fields.put("deleteSource", "第一版只支持复制导入，不删除源目录");
            p.errors.add("第一版只支持复制导入，不删除源目录");
        }
        if (source.legacy) {
            p.errors.add("源目录是旧版布局（根目录 region/DIM-1/DIM1/playerdata）："
                    + "请先用 26.3 原版打开并升级/另存为新布局，再导入；不能直接塞进栈层");
        }
        if (!source.hasLevelDat) {
            p.errors.add("源目录缺少 level.dat");
        }
        if (!source.hasDimensions) {
            p.errors.add("源目录缺少 dimensions/minecraft；请确认是 26.3 新布局");
        }
        if (!source.hasGenSettings) {
            p.fields.put("path", "源目录缺少 data/minecraft/world_gen_settings.dat");
            p.errors.add("源目录缺少 data/minecraft/world_gen_settings.dat："
                    + "26.3 必须带该文件（含 seed 与维度生成设置），否则导入后世界无法启动；"
                    + "请先用 26.3 打开/另存一次该世界");
        }
        LevelInfo serverLevel = LevelInfo.read(worldDir);
        if (source.dataVersion > 0 && serverLevel.dataVersion > 0
                && source.dataVersion > serverLevel.dataVersion) {
            p.fields.put("path", "源世界版本高于服务器世界");
            p.errors.add("源世界由更新版本创建（DataVersion " + source.dataVersion
                    + " > 服务器 " + serverLevel.dataVersion
                    + "）：服务器会拒绝打开该世界，导入后无法启动；请先用当前版本打开并另存");
        }
        if (source.snapshot && !serverLevel.snapshot) {
            p.fields.put("path", "源世界是快照版本");
            p.errors.add("源世界由快照版本创建（"
                    + (source.versionName.isBlank() ? "snapshot" : source.versionName)
                    + "）：正式版服务器会拒绝打开（incompatible version）；"
                    + "请用当前正式版打开并另存后再导入");
        }
        if (source.hasSamsaraStack) {
            p.errors.add("源目录本身包含 samsara/stack.json（世界栈存档）：请先导出单层 world");
        }
        if (source.sessionLocked) {
            p.errors.add("源目录的 session.lock 仍被占用：请先关闭正在使用该世界的客户端/服务器");
        }
        if (p.errors.isEmpty()) {
            long actualFree = freeBytes(worldDir);
            long reserved = queuedNeededBytes(queued);
            p.freeBytes = actualFree < 0 ? actualFree : Math.max(0, actualFree - reserved);
            p.neededBytes = estimateNeeded(source.sizeBytes);
            if (p.freeBytes > 0 && p.freeBytes < p.neededBytes) {
                p.errors.add("磁盘空间不足：需要约 " + human(p.neededBytes)
                        + "，扣除队列预留后可用 " + human(p.freeBytes));
            }
        }
        StackManifest manifest = virtualManifest(worldDir, queued);
        if (manifest.byId(p.position.equals("top") ? StackPaths.TOP_ID : "__none__") != null
                && p.position.equals("top")) {
            // top 总是存在，这里只用于分支可读性
        }
        if ("bottom".equals(p.position)) {
            p.targetIndex = 0;
            p.targetId = allocateId(manifest);
        } else if ("between".equals(p.position)) {
            StackLayer after = manifest.byId(p.afterId);
            if (after == null || after.top) {
                p.fields.put("afterId", "请选择一个栈顶以下的目标层");
                p.errors.add("between 需要 afterId，且不能是栈顶");
            } else {
                p.targetIndex = after.index + 1;
                p.targetId = allocateId(manifest);
                StackLayer upper = manifest.above(after);
                p.betweenUpper = upper == null ? StackPaths.TOP_ID : upper.id;
            }
        } else if ("top".equals(p.position)) {
            p.targetIndex = manifest.layers.size();
            p.targetId = StackPaths.TOP_ID;
            p.demoteId = allocateId(manifest);
        } else {
            p.fields.put("position", "position 只能是 bottom / between / top");
            p.errors.add("position 只能是 bottom / between / top");
        }
        if (pending(worldDir, PENDING_ARCHIVE) || pending(worldDir, PENDING_PROMOTE)
                || pending(worldDir, PENDING_RESTORE)) {
            p.errors.add("已有待执行的归档/栈顶交换/撤销任务；请先重启服务器完成或手动清理 pending 文件");
        }
        if (source != null && queued.stream().anyMatch(item -> source.canonicalPath
                .equalsIgnoreCase(item.source))) {
            p.errors.add("该源目录已在待导入队列中；请等待重启执行，或先在队列里取消该项");
        }
        if (Files.isRegularFile(worldDir.resolve("samsara/pending-new.json"))) {
            p.errors.add("已有 pending-new.json（新世界生成）；请先重启完成 reset new，再做导入/归档");
        }
        p.requiresRestart = true;
        p.steps.addAll(stepsFor(worldDir, p, source, queued.size()));
        p.warnings.add("导入在服务器重启时执行，源目录只读保持不变");
        p.warnings.add("运行中不会立即加载新栈维度；重启后 world list / 地图才会看到新层");
        if (!queued.isEmpty()) {
            p.warnings.add("待导入队列已有 " + queued.size() + " 项；本次为第 "
                    + (queued.size() + 1) + " 项，重启时按确认顺序依次执行（无需多次重启）");
            p.warnings.add("本次的栈层 id / 插入位置已按队列模拟；若前一项失败，后续项会在真实栈上重新定位");
        }
        if (p.position.equals("top")) {
            p.warnings.add("导入到栈顶会先把当前 world 压成 " + p.demoteId
                    + "，并保留可回滚备份；失败会尝试恢复当前 world");
        }
        if (p.playerConflicts > 0) {
            p.warnings.add("players/data 有 " + p.playerConflicts
                    + " 个同名文件：保留服务器现有版本，源文件不覆盖");
        }
        p.ok = p.errors.isEmpty();
        return p;
    }

    private static List<String> stepsFor(Path worldDir, Preview p, SourceInfo source,
                                         int queuedCount) {
        List<String> steps = new ArrayList<>();
        if ("between".equals(p.position)) {
            steps.add("插入位置：" + p.afterId + " 与 "
                    + (p.betweenUpper.isBlank() ? StackPaths.TOP_ID : p.betweenUpper)
                    + " 之间（更靠近栈顶）");
        }
        steps.add("校验源目录、布局、session.lock、可用磁盘空间");
        if ("top".equals(p.position)) {
            steps.add("备份当前 world 元数据，并把当前 world 压入 " + p.demoteId);
            steps.add("复制源 dimensions/minecraft -> world/dimensions/minecraft");
            steps.add("复制源 level.dat / data/minecraft -> world");
        } else {
            steps.add("复制源 dimensions/minecraft -> world/dimensions/samsara/" + p.targetId);
            steps.add("复制源 level.dat / world_gen_settings.dat -> world/samsara/stack/" + p.targetId);
            steps.add("归档源 data/minecraft -> 新栈层 meta/data/minecraft（不覆盖共享 data）");
        }
        steps.add("合并源 players/data -> world/players/data（冲突保留现有）");
        steps.add("记录 claims/diff/track/spawn-history 到对应栈层数据根");
        steps.add("写入 stack.json：新层逻辑位置 #" + (p.targetIndex + 1)
                + "（栈底=#1），id=" + p.targetId);
        steps.add("服务器重启时由 mod init 读取 pending-imports.json，依次执行导入队列"
                + "（本项为当前队列第 " + (queuedCount + 1) + " 项）；每项完成后写结果报告");
        return steps;
    }

    private static SourceInfo inspectSource(Path worldDir, Path serverRoot,
                                            List<Path> importRoots, String rawPath, Preview p) {
        SourceInfo info = new SourceInfo();
        if (rawPath == null || rawPath.isBlank()) {
            p.fields.put("path", "请填写服务器本地世界目录");
            p.errors.add("路径不能为空");
            return null;
        }
        Path raw;
        try {
            raw = Path.of(rawPath.trim());
        } catch (RuntimeException e) {
            p.fields.put("path", "路径格式不正确");
            p.errors.add("路径格式不正确");
            return null;
        }
        if (!raw.isAbsolute()) {
            raw = (serverRoot == null ? worldDir.getParent() : serverRoot).resolve(raw);
        }
        Path real;
        try {
            if (!Files.exists(raw)) {
                p.fields.put("path", "目录不存在");
                p.errors.add("目录不存在：" + raw);
                return null;
            }
            real = raw.toRealPath();
        } catch (IOException e) {
            p.fields.put("path", "无法 canonicalize：" + e.getMessage());
            p.errors.add("无法解析路径：" + e.getMessage());
            return null;
        }
        info.path = raw.toAbsolutePath().normalize().toString();
        info.canonicalPath = real.toString();
        if (!allowed(real, worldDir, serverRoot, importRoots)) {
            p.fields.put("path", "路径不在服务器根或 web.yml importRoots 允许范围内");
            p.errors.add("路径不在允许范围：请把所在目录加入 web.yml 的 importRoots");
            return null;
        }
        Path worldReal;
        try {
            worldReal = worldDir.toRealPath();
        } catch (IOException e) {
            worldReal = worldDir.toAbsolutePath().normalize();
        }
        if (real.startsWith(worldReal) || worldReal.startsWith(real)) {
            p.fields.put("path", "不能导入当前 world 自身或它的父目录");
            p.errors.add("不能导入当前 world 自身或它的父目录");
            return null;
        }
        info.hasLevelDat = Files.isRegularFile(real.resolve("level.dat"));
        info.hasDimensions = Files.isDirectory(real.resolve("dimensions/minecraft"));
        // 26.3 把 generator/seed 放在 data/minecraft/world_gen_settings.dat；缺了它
        // 栈顶导入会留下一个启动不了的世界（Overworld settings missing）。
        info.hasGenSettings = Files.isRegularFile(
                real.resolve("data/minecraft/world_gen_settings.dat"));
        info.legacy = !info.hasDimensions && (Files.exists(real.resolve("region"))
                || Files.isDirectory(real.resolve("DIM-1"))
                || Files.isDirectory(real.resolve("DIM1"))
                || Files.isDirectory(real.resolve("playerdata")));
        info.layout = info.legacy ? "legacy" : info.hasDimensions ? "v26.3" : "unknown";
        info.hasSamsaraStack = Files.isRegularFile(real.resolve("samsara/stack.json"));
        info.sessionLocked = sessionLocked(real.resolve("session.lock"));
        LevelInfo level = LevelInfo.read(real);
        info.dataVersion = level.dataVersion;
        info.snapshot = level.snapshot;
        info.versionName = level.versionName;
        info.worldName = level.worldName;
        info.seed = String.valueOf(level.seed);
        info.spawnX = level.spawnX;
        info.spawnY = level.spawnY;
        info.spawnZ = level.spawnZ;
        WalkStats stats = walkStats(real);
        info.sizeBytes = stats.bytes;
        info.fileCount = stats.files;
        info.playerFiles = countFiles(real.resolve("players/data"));
        p.playerConflicts = playerConflicts(worldDir, real);
        p.conflictFiles = conflictFiles(worldDir, real);
        return info;
    }

    private static boolean allowed(Path real, Path worldDir, Path serverRoot, List<Path> importRoots) {
        Path worldAbs = worldDir.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        if (serverRoot != null) {
            roots.add(serverRoot);
        } else if (worldAbs.getParent() != null) {
            roots.add(worldAbs.getParent());
        }
        if (importRoots != null) {
            roots.addAll(importRoots);
        }
        for (Path root : roots) {
            try {
                Path canonical = root.toRealPath();
                if (real.startsWith(canonical)) {
                    return true;
                }
            } catch (IOException ignored) {
                if (real.startsWith(root.toAbsolutePath().normalize())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sessionLocked(Path lock) {
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            return ignored == null;
        } catch (Exception e) {
            return true;
        }
    }

    private static int playerConflicts(Path worldDir, Path source) {
        return conflictFiles(worldDir, source).size();
    }

    private static List<String> conflictFiles(Path worldDir, Path source) {
        List<String> out = new ArrayList<>();
        Path src = source.resolve("players/data");
        Path dst = worldDir.resolve("players/data");
        if (!Files.isDirectory(src)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(src)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                Path rel = src.relativize(file);
                if (Files.exists(dst.resolve(rel))) {
                    out.add(rel.toString().replace('\\', '/'));
                }
            });
        } catch (IOException ignored) {
            // 预览阶段不阻断；真正复制时逐文件记录
        }
        out.sort(Comparator.naturalOrder());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    // ---- 排序/归档 ----

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
        Preview p = new Preview();
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
        if (!readImportQueue(worldDir).isEmpty() || pending(worldDir, PENDING_ARCHIVE)
                || pending(worldDir, PENDING_PROMOTE)) {
            p.errors.add("已有待执行任务；请先重启服务器完成或手动清理 pending 文件");
        }
        if (Files.isRegularFile(worldDir.resolve("samsara/pending-new.json"))) {
            p.errors.add("已有 pending-new.json；请先重启完成 reset new，再做归档");
        }
        if (layer != null && !layer.top) {
            p.source = new SourceInfo();
            p.source.path = relative(worldDir, layer.metaDir(worldDir));
            p.source.canonicalPath = layer.metaDir(worldDir).toString();
            p.source.layout = "stack-layer";
            p.source.sizeBytes = layerBytes(worldDir, layer);
            p.freeBytes = freeBytes(worldDir);
            p.neededBytes = p.source.sizeBytes;
            p.requiresRestart = true;
            p.backupPath = serverRelative(worldDir, archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>";
            p.steps.add("移动 " + serverRelative(worldDir, StackPaths.layerDimensions(worldDir, layer))
                    + " -> " + serverRelative(worldDir, archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>/dimensions");
            p.steps.add("移动 " + serverRelative(worldDir, layer.metaDir(worldDir))
                    + " -> " + serverRelative(worldDir, archiveRoot(worldDir))
                    + "/" + layer.id + "-<job>/meta");
            p.steps.add("从 stack.json 移除该层并重排 index；world 保持栈顶");
            p.warnings.add("归档会保留完整备份，不直接删除；重启时执行");
            p.warnings.add("该层的 claims/diff/track 随 meta 一起归档，不再参与运行");
        }
        p.ok = p.errors.isEmpty();
        return p;
    }

    public static synchronized ScheduleResult scheduleArchive(Path worldDir, String id) {
        Preview preview = previewArchive(worldDir, id);
        ScheduleResult result = new ScheduleResult();
        result.preview = preview;
        if (!preview.ok) {
            result.ok = false;
            return result;
        }
        String jobId = newJobId("arc");
        PendingArchive pending = new PendingArchive();
        pending.jobId = jobId;
        pending.id = preview.targetId;
        pending.createdAt = System.currentTimeMillis();
        pending.archivePath = archiveRoot(worldDir).resolve(pending.id + "-" + jobId).toString();
        if (!write(worldDir, PENDING_ARCHIVE, pending)) {
            result.ok = false;
            preview.errors.add("无法写入 pending-archive.json");
            return result;
        }
        Job job = newJob(jobId, "archive", preview.targetId);
        job.source = preview.source == null ? "" : preview.source.canonicalPath;
        job.requiresRestart = true;
        job.message = "已排队：重启服务器后归档 " + pending.id;
        writeJob(worldDir, job);
        result.ok = true;
        result.jobId = jobId;
        result.requiresRestart = true;
        result.job = job;
        return result;
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
        pruneBackups(worldDir, backupKeep());
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
                executePromote(worldDir, pending);
            }
            return;
        }
        if (Files.exists(archiveFile)) {
            PendingArchive pending = read(worldDir, PENDING_ARCHIVE, PendingArchive.class);
            if (pending != null) {
                executeArchive(worldDir, pending);
            }
            return;
        }
        if (!imports.isEmpty()) {
            executeImportQueue(worldDir, imports);
        }
    }

    /** 依次执行导入队列；单项失败时保留剩余项到下次重启，避免位置连锁错误。 */
    private static void executeImportQueue(Path worldDir, List<PendingImport> queue) {
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

    private static void executeArchive(Path worldDir, PendingArchive pending) {
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

    private static void executePromote(Path worldDir, PendingPromote pending) {
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

    private static boolean executeImport(Path worldDir, PendingImport pending) {
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
            if (sessionLocked(source.resolve("session.lock"))) {
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

    private static void importLayer(Path worldDir, Path source, PendingImport pending, Job job)
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

    private static void importTop(Path worldDir, Path source, PendingImport pending, Job job)
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

    private static final class LevelInfo {
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

    private static boolean skip(Path relative) {
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

    private static void moveFailed(Path worldDir, String name, Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return;
            }
            Files.move(file, file.resolveSibling(name + ".failed-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    static void moveFailedFile(Path worldDir, String name, Path file) {
        moveFailed(worldDir, name, file);
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

    private static void writeJobQuiet(Job job) {
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

    private static void markOperationFailed(Path worldDir, WorldStackTimeline.Operation operation,
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

    private static boolean write(Path worldDir, String name, Object value) {
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

    private static boolean pending(Path worldDir, String name) {
        return Files.isRegularFile(stackFile(worldDir, name));
    }

    private static Path stackFile(Path worldDir, String name) {
        return worldDir.resolve("samsara").resolve(name);
    }

    private static long freeBytes(Path worldDir) {
        try {
            return Files.getFileStore(worldDir).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private static WalkStats walkStats(Path root) {
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

    private static int countFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path root) {
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

    private static String human(long bytes) {
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

    private static String relative(Path worldDir, Path path) {
        try {
            return worldDir.relativize(path.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }

    private static String normalizePosition(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "", "bottom" -> "bottom";
            case "top" -> "top";
            case "between", "after" -> "between";
            default -> value;
        };
    }

    private static String allocateId(StackManifest manifest) {
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

    private static final class WalkStats {
        long bytes;
        int files;
    }
}
