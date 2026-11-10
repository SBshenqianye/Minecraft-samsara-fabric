package com.shenqianye.samsara.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.season.StackManifest;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 服务器本体存档备份：创建、列表、删除、恢复。
 *
 * <p>创建/恢复都在服务器停止后、下一次启动、世界打开之前执行（由
 * {@link com.shenqianye.samsara.season.StackBootstrap#prepare()} 调用），
 * 避免运行中复制 region 产生不一致的备份。恢复前会把当前 world/配置移动到
 * {@code backups/samsara/server-backups/pre-restore-<job>/}，失败自动回滚。
 *
 * <p>备份内容：world（排除 session.lock 与 web-map-cache）+ 服务器配置
 * （server.properties/ops/whitelist/banned/eula）+ config/samsara。
 * 恢复时 server.properties 与 web.yml 不直接覆盖，另存为
 * {@code *.restored-<job>} 供服主确认。
 */
public final class ServerBackup {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> ROOT_FILES = List.of(
            "server.properties", "eula.txt", "ops.json", "whitelist.json",
            "banned-players.json", "banned-ips.json");
    private static final long MIN_FREE_MARGIN = 256L * 1024 * 1024;

    private ServerBackup() {
    }

    // ---- paths ----

    public static Path serverRoot() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    }

    public static String levelName(Path serverRoot) {
        String name = "world";
        Path props = serverRoot.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            try {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("level-name=")) {
                        String value = line.substring("level-name=".length()).trim();
                        if (!value.isEmpty() && !value.contains("/") && !value.contains("\\")) {
                            name = value;
                        }
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return name;
    }

    public static Path worldDir(Path serverRoot) {
        return serverRoot.resolve(levelName(serverRoot)).toAbsolutePath().normalize();
    }

    public static Path backupRoot(Path serverRoot) {
        return ServerBackupPaths.root(serverRoot).resolve("server-backups");
    }

    public static Path jobRoot(Path serverRoot) {
        return ServerBackupPaths.root(serverRoot).resolve("server-backup-jobs");
    }

    public static Path pendingBackupFile(Path serverRoot) {
        return ServerBackupPaths.root(serverRoot).resolve("pending-server-backup.json");
    }

    public static Path pendingRestoreFile(Path serverRoot) {
        return ServerBackupPaths.root(serverRoot).resolve("pending-server-restore.json");
    }

    public static Path backupFile(Path serverRoot, String id) {
        return backupRoot(serverRoot).resolve(id + ".zip");
    }

    public static Path sidecarFile(Path serverRoot, String id) {
        return backupRoot(serverRoot).resolve(id + ".json");
    }

    public static Path stateFile(Path serverRoot) {
        return ServerBackupPaths.root(serverRoot).resolve("server-backup-state.json");
    }

    // ---- models ----

    /** 时间线状态：当前头节点，以及“恢复后下一次备份是否另起分支”。 */
    public static final class State {
        public String headId = "";
        public boolean pendingBranch;
        public long updatedAt;
    }

    public static final class Pending {
        public String mode = "backup";
        public String id = "";
        public String jobId = "";
        public int keep = 5;
        public String note = "";
        public long createdAt;
    }

    public static final class Job {
        public String id = "";
        public String type = "";
        public String status = "queued";
        public String stage = "排队";
        public int progress;
        public String message = "";
        public long createdAt;
        public long startedAt;
        public long finishedAt;
        public long totalBytes;
        public long processedBytes;
        public int fileCount;
        public String backupId = "";
        public List<String> log = new ArrayList<>();
    }

    public static final class BackupInfo {
        public String id = "";
        public String file = "";
        public String sidecar = "";
        public String worldName = "";
        public String mcVersion = "";
        public String modVersion = "";
        public String sha256 = "";
        public String error = "";
        public String parentId = "";
        public String branchId = "";
        public String message = "";
        public String note = "";
        public long createdAt;
        public long restoredAt;
        public long zipBytes;
        public long totalBytes;
        public int fileCount;
        public boolean pendingRestore;
        // 时间线渲染用，不写回 sidecar 的业务字段也可以照常序列化
        public boolean current;
        public boolean deletable = true;
        public boolean hasChildren;
        public int depth;
    }

    public static final class Preview {
        public boolean ok;
        public String id = "";
        public String worldName = "";
        public long backupBytes;
        public long worldBytes;
        public long freeBytes;
        public long neededBytes;
        public String createdAt = "";
        public String sha256 = "";
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> steps = new ArrayList<>();
        public boolean requiresRestart = true;
    }

    // ---- list / model ----

    public static synchronized Map<String, Object> model(Path serverRoot) {
        Path world = worldDir(serverRoot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("serverRoot", serverRoot.toString());
        out.put("worldDir", world.toString());
        out.put("worldName", world.getFileName().toString());
        out.put("worldBytes", sizeOf(world));
        out.put("backupRoot", backupRoot(serverRoot).toString());
        out.put("freeBytes", freeBytes(serverRoot));
        out.put("keep", currentKeep());
        out.put("backups", list(serverRoot));
        out.put("timeline", timeline(serverRoot));
        out.put("pendingBackup", read(serverRoot, pendingBackupFile(serverRoot), Pending.class));
        out.put("pendingRestore", read(serverRoot, pendingRestoreFile(serverRoot), Pending.class));
        out.put("jobs", jobs(serverRoot, 12));
        out.put("lastReport", readJob(serverRoot, "last-result"));
        out.put("configFiles", ServerConfigFiles.readAll(serverRoot));
        return out;
    }

    /** 备份时间线：父节点 + 分支 + 当前头节点，供本体与其它备份系统复用。 */
    public static synchronized Map<String, Object> timeline(Path serverRoot) {
        List<BackupTimeline.Entry> entries = new ArrayList<>();
        for (BackupInfo info : list(serverRoot)) {
            BackupTimeline.Entry entry = new BackupTimeline.Entry();
            entry.id = info.id;
            entry.parentId = info.parentId;
            entry.branchId = info.branchId;
            entry.label = info.worldName;
            entry.createdAt = info.createdAt;
            entry.data.put("worldName", info.worldName);
            entry.data.put("zipBytes", info.zipBytes);
            entry.data.put("totalBytes", info.totalBytes);
            entry.data.put("fileCount", info.fileCount);
            entry.data.put("sha256", info.sha256);
            entry.data.put("error", info.error);
            entry.data.put("restoredAt", info.restoredAt);
            entry.data.put("pendingRestore", info.pendingRestore);
            entry.data.put("note", info.note);
            if (info.note != null && !info.note.isBlank()) entry.label = info.note;
            entries.add(entry);
        }
        State state = readState(serverRoot);
        BackupTimeline.Tree tree = BackupTimeline.build(entries, state.headId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headId", tree.headId);
        out.put("pendingBranch", state.pendingBranch);
        out.put("nodes", tree.nodes);
        return out;
    }

    public static synchronized List<BackupInfo> list(Path serverRoot) {        List<BackupInfo> out = new ArrayList<>();
        Path root = backupRoot(serverRoot);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            for (Path file : (Iterable<Path>) stream.sorted()::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") || name.equals("last-result.json")) {
                    continue;
                }
                String id = name.substring(0, name.length() - 5);
                BackupInfo info = readSidecar(serverRoot, id);
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[本体备份] 读取备份目录失败: {}", e.getMessage());
        }
        out.sort(Comparator.comparingLong((BackupInfo b) -> b.createdAt).reversed());
        return out;
    }

    public static synchronized BackupInfo info(Path serverRoot, String id) {
        BackupInfo info = readSidecar(serverRoot, id);
        if (info != null) {
            return info;
        }
        Path zip = backupFile(serverRoot, id);
        if (!Files.isRegularFile(zip)) {
            return null;
        }
        return fromZipManifest(serverRoot, zip, id);
    }

    private static BackupInfo readSidecar(Path serverRoot, String id) {
        Path sidecar = sidecarFile(serverRoot, id);
        if (!Files.isRegularFile(sidecar)) {
            Path zip = backupFile(serverRoot, id);
            return Files.isRegularFile(zip) ? fromZipManifest(serverRoot, zip, id) : null;
        }
        try {
            BackupInfo info = GSON.fromJson(Files.readString(sidecar, StandardCharsets.UTF_8),
                    BackupInfo.class);
            if (info == null) {
                return null;
            }
            info.pendingRestore = Files.isRegularFile(pendingRestoreFile(serverRoot))
                    && id.equals(readPendingRestoreId(serverRoot));
            return info;
        } catch (Exception e) {
            BackupInfo info = fromZipManifest(serverRoot, backupFile(serverRoot, id), id);
            if (info != null) {
                info.error = "sidecar 解析失败：" + e.getMessage();
            }
            return info;
        }
    }

    private static String readPendingRestoreId(Path serverRoot) {
        Pending pending = read(serverRoot, pendingRestoreFile(serverRoot), Pending.class);
        return pending == null ? "" : pending.id;
    }

    private static BackupInfo fromZipManifest(Path serverRoot, Path zip, String id) {
        try {
            Map<String, Object> manifest = readManifest(zip);
            if (manifest == null) {
                return null;
            }
            BackupInfo info = new BackupInfo();
            info.id = id;
            info.file = zip.toString();
            info.sidecar = sidecarFile(serverRoot, id).toString();
            info.worldName = str(manifest.get("worldName"));
            info.mcVersion = str(manifest.get("mcVersion"));
            info.modVersion = str(manifest.get("modVersion"));
            info.sha256 = str(manifest.get("sha256"));
            info.createdAt = num(manifest.get("createdAt"));
            info.zipBytes = Files.size(zip);
            info.totalBytes = num(manifest.get("totalBytes"));
            info.fileCount = (int) num(manifest.get("fileCount"));
            info.note = str(manifest.get("note"));
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- schedule ----

    public static synchronized Map<String, Object> previewCreate(Path serverRoot) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path world = worldDir(serverRoot);
        long worldBytes = sizeOf(world);
        long free = freeBytes(serverRoot);
        out.put("ok", Files.isDirectory(world) && free > worldBytes + MIN_FREE_MARGIN);
        out.put("worldDir", world.toString());
        out.put("worldName", world.getFileName().toString());
        out.put("worldBytes", worldBytes);
        out.put("freeBytes", free);
        out.put("neededBytes", worldBytes + MIN_FREE_MARGIN);
        out.put("keep", currentKeep());
        out.put("steps", List.of(
                "停服后、下一次启动前打包 world（排除 session.lock / web-map-cache）",
                "同时打包 server.properties、白名单/OP/封禁、eula 与 config/samsara",
                "写入服务器级 backups/samsara/server-backups/<id>.zip，并生成 manifest",
                "按 serverBackupKeep 只清理本功能创建的旧备份，源 world 永不自动删除"));
        if (!Files.isDirectory(world)) {
            out.put("error", "world 目录不存在");
        } else if (free <= worldBytes + MIN_FREE_MARGIN) {
            out.put("error", "磁盘空间不足：world 约 " + human(worldBytes)
                    + "，可用 " + human(free) + "，至少还需要 256MB 余量");
        }
        return out;
    }

    public static synchronized Map<String, Object> scheduleCreate(Path serverRoot) {
        return scheduleCreate(serverRoot, "");
    }

    public static synchronized Map<String, Object> scheduleCreate(Path serverRoot, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pending(serverRoot)) {
            out.put("ok", false);
            out.put("error", "已有待执行的备份/恢复任务；请先重启完成");
            return out;
        }
        if (stackPending(serverRoot)) {
            out.put("ok", false);
            out.put("error", "存在待执行的导入/归档/新世界任务；请先重启完成再做本体备份");
            return out;
        }
        Map<String, Object> preview = previewCreate(serverRoot);
        if (!Boolean.TRUE.equals(preview.get("ok"))) {
            out.put("ok", false);
            out.put("error", preview.getOrDefault("error", "备份预览未通过"));
            return out;
        }
        Pending pending = new Pending();
        pending.mode = "backup";
        pending.id = newId("sb");
        pending.jobId = newId("sbjob");
        pending.keep = currentKeep();
        pending.note = note == null ? "" : note.trim();
        pending.createdAt = System.currentTimeMillis();
        Job job = newJob(pending.jobId, "backup", pending.id);
        job.message = "已排队：停服后、下次启动前创建本体备份 " + pending.id
                + (pending.note.isBlank() ? "" : "（" + pending.note + "）");
        writeJob(serverRoot, job);
        if (!write(serverRoot, pendingBackupFile(serverRoot), pending)) {
            out.put("ok", false);
            out.put("error", "无法写入 pending-server-backup.json");
            return out;
        }
        out.put("ok", true);
        out.put("requiresRestart", true);
        out.put("jobId", pending.jobId);
        out.put("id", pending.id);
        out.put("job", job);
        out.put("message", job.message);
        return out;
    }

    public static synchronized Preview previewRestore(Path serverRoot, String id) {
        Preview preview = new Preview();
        preview.id = id == null ? "" : id.trim();
        BackupInfo info = info(serverRoot, preview.id);
        if (info == null) {
            preview.errors.add("备份不存在：" + preview.id);
            return preview;
        }
        Map<String, Object> manifest = readManifest(Path.of(info.file));
        if (manifest == null) {
            preview.errors.add("备份缺少 manifest.json");
            return preview;
        }
        Path world = worldDir(serverRoot);
        preview.worldName = str(manifest.get("worldName"));
        preview.backupBytes = info.zipBytes;
        preview.worldBytes = sizeOf(world);
        preview.freeBytes = freeBytes(serverRoot);
        preview.sha256 = info.sha256;
        preview.createdAt = String.valueOf(num(manifest.get("createdAt")));
        preview.neededBytes = preview.worldBytes + MIN_FREE_MARGIN;
        if (!preview.worldName.equals(world.getFileName().toString())) {
            preview.errors.add("备份的 level-name=" + preview.worldName
                    + "，当前 level-name=" + world.getFileName());
        }
        if (preview.freeBytes > 0 && preview.freeBytes < preview.neededBytes) {
            preview.errors.add("磁盘空间不足：当前 world 约 " + human(preview.worldBytes)
                    + "，可用 " + human(preview.freeBytes));
        }
        preview.steps.add("停服后、下次启动前执行");
        preview.steps.add("把当前 world 移到 backupRoot/pre-restore-<job>/world（失败可回滚）");
        preview.steps.add("解压备份的 world 覆盖当前 world");
        preview.steps.add("恢复 whitelist/ops/banned/eula 与 config/samsara");
        preview.steps.add("server.properties 与 web.yml 不直接覆盖，另存为 *.restored-<job> 供确认");
        preview.warnings.add("恢复会替换当前世界；建议先在游戏内确认没有正在进行的导入/归档");
        preview.warnings.add("恢复完成后需要再次启动服务器才会加载新世界");
        preview.warnings.add("时间线会移动到该备份；下一次备份会从该点分出新分支");
        preview.ok = preview.errors.isEmpty();
        return preview;
    }

    public static synchronized Map<String, Object> scheduleRestore(Path serverRoot, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pending(serverRoot)) {
            out.put("ok", false);
            out.put("error", "已有待执行的备份/恢复任务；请先重启完成");
            return out;
        }
        if (stackPending(serverRoot)) {
            out.put("ok", false);
            out.put("error", "存在待执行的导入/归档/新世界任务；请先重启完成再恢复本体备份");
            return out;
        }
        Preview preview = previewRestore(serverRoot, id);
        if (!preview.ok) {
            out.put("ok", false);
            out.put("error", String.join("；", preview.errors));
            out.put("preview", preview);
            return out;
        }
        Pending pending = new Pending();
        pending.mode = "restore";
        pending.id = preview.id;
        pending.jobId = newId("rsjob");
        pending.keep = currentKeep();
        pending.createdAt = System.currentTimeMillis();
        Job job = newJob(pending.jobId, "restore", preview.id);
        job.message = "已排队：停服后、下次启动前恢复 " + preview.id;
        writeJob(serverRoot, job);
        if (!write(serverRoot, pendingRestoreFile(serverRoot), pending)) {
            out.put("ok", false);
            out.put("error", "无法写入 pending-server-restore.json");
            return out;
        }
        out.put("ok", true);
        out.put("requiresRestart", true);
        out.put("jobId", pending.jobId);
        out.put("id", pending.id);
        out.put("job", job);
        out.put("preview", preview);
        return out;
    }

    public static synchronized Map<String, Object> delete(Path serverRoot, String id,
                                                          boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!confirmed) {
            out.put("ok", false);
            out.put("error", "删除需要二次确认");
            return out;
        }
        Pending pendingRestore = read(serverRoot, pendingRestoreFile(serverRoot), Pending.class);
        if (pendingRestore != null && id.equals(pendingRestore.id)) {
            out.put("ok", false);
            out.put("error", "该备份已排队恢复，不能删除");
            return out;
        }
        Path zip = backupFile(serverRoot, id);
        Path sidecar = sidecarFile(serverRoot, id);
        if (!Files.isRegularFile(zip) && !Files.isRegularFile(sidecar)) {
            out.put("ok", false);
            out.put("error", "备份不存在：" + id);
            return out;
        }
        State state = readState(serverRoot);
        Object timelineHead = timeline(serverRoot).get("headId");
        String headId = timelineHead == null ? state.headId : String.valueOf(timelineHead);
        if (id.equals(headId)) {
            out.put("ok", false);
            out.put("error", "该备份是当前时间线节点，先回滚到别处或先创建新备份，再删除");
            return out;
        }
        boolean hasChildren = list(serverRoot).stream()
                .anyMatch(backup -> id.equals(backup.parentId));
        if (hasChildren) {
            out.put("ok", false);
            out.put("error", "该备份还有后续分支，不能删除；请先删除它的子节点");
            return out;
        }
        try {
            Files.deleteIfExists(zip);
            Files.deleteIfExists(sidecar);
            out.put("ok", true);
            out.put("message", "已删除备份 " + id);
        } catch (IOException e) {
            out.put("ok", false);
            out.put("error", "删除失败：" + e.getMessage());
        }
        return out;
    }

    public static synchronized List<Job> jobs(Path serverRoot, int limit) {
        List<Job> out = new ArrayList<>();
        Path root = jobRoot(serverRoot);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            for (Path file : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                if (!file.getFileName().toString().endsWith(".json")
                        || file.getFileName().toString().equals("last-result.json")) {
                    continue;
                }
                Job job = readJobFile(file);
                if (job != null) {
                    out.add(job);
                    if (out.size() >= Math.max(1, limit)) {
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static synchronized Job job(Path serverRoot, String id) {
        if (id == null || id.isBlank()) {
            return readJob(serverRoot, "last-result");
        }
        if ("last-result".equals(id)) {
            return readJob(serverRoot, "last-result");
        }
        Path file = jobRoot(serverRoot).resolve(id + ".json");
        return readJobFile(file);
    }

    // ---- boot execution ----

    /** 世界打开前调用；处理 pending-server-backup / pending-server-restore。 */
    public static synchronized void prepare() {
        prepare(serverRoot());
    }

    public static synchronized void prepare(Path serverRoot) {
        ServerBackupPaths.migrateLegacyRoot(serverRoot);
        Path backupPending = pendingBackupFile(serverRoot);
        Path restorePending = pendingRestoreFile(serverRoot);
        boolean hasBackup = Files.isRegularFile(backupPending);
        boolean hasRestore = Files.isRegularFile(restorePending);
        if (!hasBackup && !hasRestore) {
            return;
        }
        if (hasBackup && hasRestore) {
            Job job = newJob("sbfail-" + System.currentTimeMillis(), "backup", "");
            job.status = "failed";
            job.message = "同时存在本体备份与恢复任务，拒绝自动执行";
            writeJob(serverRoot, job);
            moveFailed(serverRoot, backupPending);
            moveFailed(serverRoot, restorePending);
            Samsara.LOGGER.error("[本体备份] {}", job.message);
            return;
        }
        if (stackPending(serverRoot)) {
            Job job = newJob(readPendingJobId(hasRestore ? restorePending : backupPending),
                    hasRestore ? "restore" : "backup", "");
            job.status = "failed";
            job.message = "存在待执行的世界栈任务，拒绝本体备份/恢复";
            writeJob(serverRoot, job);
            moveFailed(serverRoot, backupPending);
            moveFailed(serverRoot, restorePending);
            Samsara.LOGGER.error("[本体备份] {}", job.message);
            return;
        }
        if (hasRestore) {
            Pending pending = read(serverRoot, restorePending, Pending.class);
            if (pending != null) {
                executeRestore(serverRoot, pending);
            }
            return;
        }
        Pending pending = read(serverRoot, backupPending, Pending.class);
        if (pending != null) {
            executeBackup(serverRoot, pending);
        }
    }

    private static State readState(Path serverRoot) {
        State state = read(serverRoot, stateFile(serverRoot), State.class);
        return state == null ? new State() : state;
    }

    private static void writeState(Path serverRoot, State state) {
        state.updatedAt = System.currentTimeMillis();
        write(serverRoot, stateFile(serverRoot), state);
    }

    private static void executeBackup(Path serverRoot, Pending pending) {
        Path pendingFile = pendingBackupFile(serverRoot);
        Path world = worldDir(serverRoot);
        Job job = readJob(serverRoot, pending.jobId);
        if (job == null) {
            job = newJob(pending.jobId, "backup", pending.id);
        }
        job.status = "running";
        job.stage = "打包 world 与服务器配置";
        job.startedAt = System.currentTimeMillis();
        job.backupId = pending.id;
        writeJob(serverRoot, job);
        Path root = backupRoot(serverRoot);
        Path target = backupFile(serverRoot, pending.id);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!Files.isDirectory(world)) {
                throw new IOException("world 目录不存在：" + world);
            }
            Files.createDirectories(root);
            Files.deleteIfExists(tmp);
            Map<String, Object> manifest = createZip(serverRoot, world, tmp, job, pending.note);
            manifest.put("id", pending.id);
            manifest.put("createdAt", pending.createdAt > 0 ? pending.createdAt : System.currentTimeMillis());
            manifest.put("note", pending.note == null ? "" : pending.note);
            manifest.put("sha256", sha256(tmp));
            manifest.put("zipBytes", Files.size(tmp));
            // 重新写 manifest 需要重打包；这里把最新 manifest 写到 sidecar，
            // zip 内 manifest 已包含主要字段，sha256 以 sidecar 为准。
            State state = readState(serverRoot);
            List<BackupInfo> existing = list(serverRoot);
            String parentId = state.headId;
            boolean parentExists = false;
            for (BackupInfo backup : existing) {
                if (backup.id.equals(parentId)) {
                    parentExists = true;
                    break;
                }
            }
            if (parentId == null || parentId.isBlank() || !parentExists) {
                parentId = existing.isEmpty() ? "" : existing.get(0).id;
            }
            BackupInfo parentInfo = null;
            boolean parentHasChildren = false;
            for (BackupInfo backup : existing) {
                if (backup.id.equals(parentId)) {
                    parentInfo = backup;
                }
                if (parentId.equals(backup.parentId)) {
                    parentHasChildren = true;
                }
            }
            parentHasChildren = parentHasChildren && !parentId.isEmpty();
            String branchId;
            if (parentId.isEmpty()) {
                branchId = newId("br");
            } else if (state.pendingBranch || parentHasChildren) {
                branchId = newId("br");
            } else if (parentInfo != null && parentInfo.branchId != null
                    && !parentInfo.branchId.isBlank()) {
                branchId = parentInfo.branchId;
            } else {
                branchId = "main";
            }
            BackupInfo info = new BackupInfo();
            info.id = pending.id;
            info.file = target.toString();
            info.sidecar = sidecarFile(serverRoot, pending.id).toString();
            info.worldName = str(manifest.get("worldName"));
            info.mcVersion = str(manifest.get("mcVersion"));
            info.modVersion = str(manifest.get("modVersion"));
            info.sha256 = str(manifest.get("sha256"));
            info.createdAt = num(manifest.get("createdAt"));
            info.zipBytes = num(manifest.get("zipBytes"));
            info.totalBytes = num(manifest.get("totalBytes"));
            info.fileCount = (int) num(manifest.get("fileCount"));
            info.parentId = parentId;
            info.branchId = branchId;
            info.note = pending.note == null ? "" : pending.note;
            info.message = info.note.isBlank()
                    ? (parentId.isEmpty() ? "时间线起点" : "从 " + parentId + " 继续")
                    : info.note;
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(sidecarFile(serverRoot, pending.id), GSON.toJson(info),
                    StandardCharsets.UTF_8);
            state.headId = pending.id;
            state.pendingBranch = false;
            writeState(serverRoot, state);
            prune(serverRoot, pending.keep);
            Files.deleteIfExists(pendingFile);
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.processedBytes = info.totalBytes;
            job.totalBytes = info.totalBytes;
            job.fileCount = info.fileCount;
            job.finishedAt = System.currentTimeMillis();
            job.message = "本体备份完成：" + target;
            job.log.add("zip=" + target + " (" + human(info.zipBytes) + ")");
            job.log.add("world=" + info.worldName + " · files=" + info.fileCount
                    + " · raw=" + human(info.totalBytes));
            writeJob(serverRoot, job);
            Samsara.LOGGER.info("[本体备份] {}", job.message);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "本体备份失败：" + e.getMessage();
            job.log.add(job.message);
            writeJob(serverRoot, job);
            moveFailed(serverRoot, pendingFile);
            Samsara.LOGGER.error("[本体备份] {}", job.message);
        }
    }

    private static void executeRestore(Path serverRoot, Pending pending) {
        Path pendingFile = pendingRestoreFile(serverRoot);
        Path world = worldDir(serverRoot);
        Path zip = backupFile(serverRoot, pending.id);
        Job job = readJob(serverRoot, pending.jobId);
        if (job == null) {
            job = newJob(pending.jobId, "restore", pending.id);
        }
        job.status = "running";
        job.stage = "校验备份与磁盘空间";
        job.startedAt = System.currentTimeMillis();
        job.backupId = pending.id;
        writeJob(serverRoot, job);
        Path rollback = backupRoot(serverRoot).resolve("pre-restore-" + pending.jobId);
        Path staging = backupRoot(serverRoot).resolve("restore-" + pending.jobId);
        boolean movedWorld = false;
        boolean movedRollback = false;
        try {
            if (!Files.isRegularFile(zip)) {
                throw new IOException("备份文件不存在：" + zip);
            }
            Map<String, Object> manifest = readManifest(zip);
            if (manifest == null) {
                throw new IOException("备份缺少 manifest.json");
            }
            String backupWorld = str(manifest.get("worldName"));
            if (!backupWorld.equals(world.getFileName().toString())) {
                throw new IOException("备份 level-name=" + backupWorld
                        + " 与当前 " + world.getFileName() + " 不一致");
            }
            long free = freeBytes(serverRoot);
            long worldBytes = sizeOf(world);
            if (free < worldBytes + MIN_FREE_MARGIN) {
                throw new IOException("磁盘空间不足：当前 world 约 " + human(worldBytes)
                        + "，可用 " + human(free));
            }
            deleteRecursive(staging);
            deleteRecursive(rollback);
            Files.createDirectories(rollback);
            if (Files.exists(world)) {
                Files.move(world, rollback.resolve("world"), StandardCopyOption.ATOMIC_MOVE);
                movedWorld = true;
            }
            copyTree(serverRoot.resolve("config/samsara"), rollback.resolve("config-samsara"));
            for (String name : ROOT_FILES) {
                Path file = serverRoot.resolve(name);
                if (Files.isRegularFile(file)) {
                    copyFile(file, rollback.resolve("config").resolve(name));
                }
            }
            movedRollback = true;
            job.stage = "解压备份";
            job.progress = 20;
            writeJob(serverRoot, job);
            Files.createDirectories(staging);
            extractZip(zip, staging, job);
            Path restoredWorld = staging.resolve("world");
            if (!Files.isDirectory(restoredWorld)) {
                throw new IOException("备份中没有 world/");
            }
            job.stage = "替换 world 与配置";
            job.progress = 80;
            writeJob(serverRoot, job);
            Files.move(restoredWorld, world, StandardCopyOption.ATOMIC_MOVE);
            restoreConfigs(serverRoot, staging, pending.jobId, job);
            State state = readState(serverRoot);
            state.headId = pending.id;
            state.pendingBranch = true;
            writeState(serverRoot, state);
            BackupInfo restoredInfo = info(serverRoot, pending.id);
            if (restoredInfo != null) {
                restoredInfo.restoredAt = System.currentTimeMillis();
                Files.writeString(sidecarFile(serverRoot, pending.id), GSON.toJson(restoredInfo),
                        StandardCharsets.UTF_8);
            }
            deleteRecursive(staging);
            Files.deleteIfExists(pendingFile);
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.finishedAt = System.currentTimeMillis();
            job.message = "已恢复 " + pending.id + "；恢复前 world 保留在 " + rollback;
            job.log.add("rollback=" + rollback);
            job.log.add("时间线已移动到 " + pending.id + "；下一次备份会从该点分出新分支");
            job.log.add("server.properties / web.yml 未覆盖，另存为 *.restored-" + pending.jobId);
            writeJob(serverRoot, job);
            Samsara.LOGGER.info("[本体备份] {}", job.message);
        } catch (Exception e) {
            try {
                if (movedWorld) {
                    deleteRecursive(world);
                    Files.move(rollback.resolve("world"), world, StandardCopyOption.ATOMIC_MOVE);
                }
                if (movedRollback) {
                    restoreRollbackConfigs(serverRoot, rollback);
                }
                deleteRecursive(staging);
                job.log.add("已回滚到恢复前状态");
            } catch (Exception rollbackError) {
                job.log.add("恢复回滚失败：" + rollbackError.getMessage());
            }
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "本体恢复失败：" + e.getMessage();
            job.log.add(job.message);
            writeJob(serverRoot, job);
            moveFailed(serverRoot, pendingFile);
            Samsara.LOGGER.error("[本体备份] {}", job.message);
        }
    }

    private static void restoreConfigs(Path serverRoot, Path staging, String jobId, Job job)
            throws IOException {
        Path configSamsara = staging.resolve("config-samsara");
        if (Files.isDirectory(configSamsara)) {
            try (var stream = Files.walk(configSamsara)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    Path rel = configSamsara.relativize(file);
                    String name = rel.toString().replace('\\', '/');
                    Path target = "web.yml".equals(name)
                            ? serverRoot.resolve("config/samsara/web.yml.restored-" + jobId)
                            : serverRoot.resolve("config/samsara").resolve(rel);
                    copyFile(file, target);
                }
            }
        }
        for (String name : ROOT_FILES) {
            Path file = staging.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Path target = "server.properties".equals(name)
                    ? serverRoot.resolve("server.properties.restored-" + jobId)
                    : serverRoot.resolve(name);
            copyFile(file, target);
        }
        job.log.add("配置已恢复；server.properties / web.yml 另存供确认");
    }

    private static void restoreRollbackConfigs(Path serverRoot, Path rollback) throws IOException {
        Path config = rollback.resolve("config");
        if (Files.isDirectory(config)) {
            try (var stream = Files.list(config)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    copyFile(file, serverRoot.resolve(file.getFileName().toString()));
                }
            }
        }
        Path configSamsara = rollback.resolve("config-samsara");
        if (Files.isDirectory(configSamsara)) {
            copyTree(configSamsara, serverRoot.resolve("config/samsara"));
        }
    }

    // ---- zip ----

    private static Map<String, Object> createZip(Path serverRoot, Path world, Path out, Job job,
                                                String note) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("worldName", world.getFileName().toString());
        manifest.put("mcVersion", mcVersion());
        manifest.put("modVersion", modVersion());
        manifest.put("note", note == null ? "" : note);
        manifest.put("createdAt", System.currentTimeMillis());
        try {
            StackManifest stacks = StackManifest.load(world);
            manifest.put("stackSummary", stacks.summary());
        } catch (RuntimeException ignored) {
            manifest.put("stackSummary", "");
        }
        long[] stats = new long[]{0L, 0L}; // bytes, files
        try (OutputStream raw = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            addTree(zip, world, "world", stats, job);
            for (String name : ROOT_FILES) {
                Path file = serverRoot.resolve(name);
                if (Files.isRegularFile(file)) {
                    addFile(zip, file, name, stats);
                }
            }
            addTree(zip, serverRoot.resolve("config/samsara"), "config-samsara", stats, job);
            manifest.put("totalBytes", stats[0]);
            manifest.put("fileCount", stats[1]);
            manifest.put("sha256", "");
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        job.totalBytes = stats[0];
        job.fileCount = (int) stats[1];
        job.progress = 95;
        writeJob(serverRoot, job);
        return manifest;
    }

    private static void addTree(ZipOutputStream zip, Path base, String prefix, long[] stats,
                                Job job) throws IOException {
        if (!Files.isDirectory(base)) {
            return;
        }
        try (var stream = Files.walk(base)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Path rel = base.relativize(file);
                String name = prefix + "/" + rel.toString().replace('\\', '/');
                if (skip(name)) {
                    continue;
                }
                addFile(zip, file, name, stats);
                if (stats[1] % 200 == 0) {
                    job.processedBytes = stats[0];
                    job.progress = Math.min(90, 5 + (int) Math.min(85, stats[0] / (8L * 1024 * 1024)));
                    writeJobLater(job);
                }
            }
        }
    }

    private static void addFile(ZipOutputStream zip, Path file, String name, long[] stats)
            throws IOException {
        if (skip(name)) {
            return;
        }
        ZipEntry entry = new ZipEntry(name);
        try {
            entry.setLastModifiedTime(Files.getLastModifiedTime(file));
        } catch (IOException ignored) {
        }
        zip.putNextEntry(entry);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                zip.write(buffer, 0, read);
                stats[0] += read;
            }
        }
        zip.closeEntry();
        stats[1]++;
    }

    private static boolean skip(String name) {
        if ("world/session.lock".equals(name)) {
            return true;
        }
        return name.startsWith("world/samsara/web-map-cache/");
    }

    private static Map<String, Object> readManifest(Path zipFile) {
        if (!Files.isRegularFile(zipFile)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return GSON.fromJson(json, new TypeToken<Map<String, Object>>() { }.getType());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void extractZip(Path zipFile, Path staging, Job job) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            var entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.equals("manifest.json") || name.startsWith("__MACOSX/")) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("..")) {
                    throw new IOException("备份包含非法路径：" + name);
                }
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging.normalize())) {
                    throw new IOException("备份路径越界：" + name);
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
                count++;
                if (count % 200 == 0) {
                    job.progress = Math.min(79, 20 + count / 200);
                    job.processedBytes += 0;
                    writeJobLater(job);
                }
            }
        }
    }

    private static void prune(Path serverRoot, int keep) {
        if (keep <= 0) {
            return;
        }
        List<BackupInfo> backups = list(serverRoot);
        List<BackupTimeline.Entry> entries = new ArrayList<>();
        for (BackupInfo backup : backups) {
            BackupTimeline.Entry entry = new BackupTimeline.Entry();
            entry.id = backup.id;
            entry.parentId = backup.parentId;
            entry.createdAt = backup.createdAt;
            entries.add(entry);
        }
        State state = readState(serverRoot);
        Set<String> kept = BackupTimeline.retentionKeep(entries, keep, state.headId);
        for (BackupInfo old : backups) {
            if (kept.contains(old.id)) {
                continue;
            }
            try {
                Files.deleteIfExists(Path.of(old.file));
                Files.deleteIfExists(Path.of(old.sidecar));
            } catch (Exception e) {
                Samsara.LOGGER.warn("[本体备份] 清理旧备份失败 {}: {}", old.id, e.getMessage());
            }
        }
    }

    // ---- helpers ----

    private static boolean pending(Path serverRoot) {
        return Files.isRegularFile(pendingBackupFile(serverRoot))
                || Files.isRegularFile(pendingRestoreFile(serverRoot));
    }

    private static boolean stackPending(Path serverRoot) {
        Path world = worldDir(serverRoot);
        Path samsara = world.resolve("samsara");
        return Files.isRegularFile(samsara.resolve("pending-import.json"))
                || Files.isRegularFile(samsara.resolve("pending-imports.json"))
                || Files.isRegularFile(samsara.resolve("pending-archive.json"))
                || Files.isRegularFile(samsara.resolve("pending-promote.json"))
                || Files.isRegularFile(samsara.resolve("pending-stack-restore.json"))
                || Files.isRegularFile(samsara.resolve("pending-new.json"));
    }

    public static boolean hasPending(Path serverRoot) {
        return pending(serverRoot);
    }

    public static int currentKeep() {
        try {
            int value = ConfigManager.getInstance().integer(ConfigSchema.WEB, "serverBackupKeep");
            return Math.max(0, value);
        } catch (RuntimeException e) {
            return 5;
        }
    }

    private static Job newJob(String id, String type, String backupId) {
        Job job = new Job();
        job.id = id;
        job.type = type;
        job.backupId = backupId;
        job.status = "queued";
        job.stage = "排队";
        job.createdAt = System.currentTimeMillis();
        return job;
    }

    private static void writeJobLater(Job job) {
        // 进度写盘交给 execute 中的周期性 writeJob；这里只更新内存，
        // 避免解压/打包时每 200 个文件都落盘。
    }

    private static void writeJob(Path serverRoot, Job job) {
        try {
            Path root = jobRoot(serverRoot);
            Files.createDirectories(root);
            String json = GSON.toJson(job);
            Files.writeString(root.resolve(job.id + ".json"), json, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("last-result.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[本体备份] 任务报告写入失败: {}", e.getMessage());
        }
    }

    private static Job readJob(Path serverRoot, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return readJobFile(jobRoot(serverRoot).resolve(id + ".json"));
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

    private static String readPendingJobId(Path file) {
        Pending pending = read(null, file, Pending.class);
        return pending == null ? "" : pending.jobId;
    }

    private static <T> T read(Path serverRoot, Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean write(Path serverRoot, Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[本体备份] {} 写入失败: {}", file, e.getMessage());
            return false;
        }
    }

    private static void moveFailed(Path serverRoot, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".failed-"
                    + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static void copyFile(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            return;
        }
        try (var stream = Files.walk(src)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Path rel = src.relativize(file);
                copyFile(file, dst.resolve(rel));
            }
        }
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static long sizeOf(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        if (Files.isRegularFile(path)) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }
        long[] total = new long[]{0L};
        try (var stream = Files.walk(path)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    total[0] += Files.size(file);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return total[0];
    }

    private static long freeBytes(Path serverRoot) {
        try {
            return Files.getFileStore(serverRoot).getUsableSpace();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String newId(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String mcVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("samsara")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }
}
