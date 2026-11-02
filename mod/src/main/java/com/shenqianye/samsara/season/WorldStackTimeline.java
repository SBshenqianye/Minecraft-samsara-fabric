package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.server.BackupTimeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 世界栈操作快照、线性时间线与“撤销这次操作”。
 *
 * <p>导入 / 提升栈顶 / 归档在时间上线性，不能分支。每个操作在备份目录写
 * {@code operation.json}，记录操作前后的完整 stack.json、受影响层与撤销所需路径。
 * 时间线复用 {@link BackupTimeline}，但 {@code branchId} 固定为 {@code world-stack}。
 * 撤销只排队，由 {@code StackBootstrap} 在世界打开前消费；执行前先把当前受影响
 * 的栈层/顶层/玩家数据移到 {@code pre-restore-<job>}，失败回滚。源世界、归档与
 * 用户备份永不自动删除。
 */
public final class WorldStackTimeline {
    public static final String PENDING_RESTORE = WorldStackAdmin.PENDING_RESTORE;
    public static final String BRANCH_ID = "world-stack";
    public static final String FILE_NAME = "operation.json";
    public static final String PRE_RESTORE_PREFIX = "pre-restore-";
    public static final String RESTORE_STAGING_PREFIX = "restore-";

    public static Path pendingFile(Path worldDir) {
        return worldDir.resolve("samsara").resolve(PENDING_RESTORE);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MIN_FREE_MARGIN = 64L * 1024 * 1024;
    private static final List<String> TOP_ITEMS = List.of(
            "dimensions/minecraft",
            "level.dat",
            "level.dat_old",
            "data/minecraft",
            "samsara/claims",
            "samsara/diff",
            "samsara/track",
            "samsara/spawn-history.json");

    private WorldStackTimeline() {
    }

    // ---- 模型 ----

    public static final class Operation {
        public int format = 1;
        public String id = "";
        public String operation = "";
        public String jobId = "";
        public long createdAt;
        public StackManifest beforeManifest = new StackManifest();
        public StackManifest afterManifest = new StackManifest();
        public List<String> affectedIds = new ArrayList<>();
        public boolean restorable;
        public String backupDir = "";
        public List<String> notes = new ArrayList<>();
        public String failReason = "";
        public long restoredAt;
        public String restoreJobId = "";
        public String sourcePath = "";
        public String archivePath = "";
        public List<String> addedPlayers = new ArrayList<>();
        public List<String> createdLayerIds = new ArrayList<>();
        public List<String> removedLayerIds = new ArrayList<>();
    }

    public static final class PendingRestore {
        public int format = 1;
        public String id = "";
        public String jobId = "";
        public String operationId = "";
        public String backupDir = "";
        public long createdAt;
        public boolean confirm;
    }

    public static final class Preview {
        public boolean ok;
        public String id = "";
        public String operation = "";
        public String jobId = "";
        public String backupDir = "";
        public long createdAt;
        public boolean requiresRestart = true;
        public boolean restorable;
        public boolean legacy;
        public String reason = "";
        public long backupBytes;
        public long currentBytes;
        public long freeBytes;
        public long neededBytes;
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> steps = new ArrayList<>();
        public final List<String> affectedIds = new ArrayList<>();
        public final List<Map<String, Object>> beforeLayers = new ArrayList<>();
        public final List<Map<String, Object>> afterLayers = new ArrayList<>();
        public final List<String> beforeSummary = new ArrayList<>();
        public final List<String> afterSummary = new ArrayList<>();
    }

    private static final class Node {
        String id = "";
        String jobId = "";
        String operation = "";
        long createdAt;
        Path dir;
        Operation op;
        WorldStackAdmin.Job job;
        boolean restore;
    }

    private static final class Candidate {
        final String rel;
        final String pre;

        Candidate(String rel, String pre) {
            this.rel = rel;
            this.pre = pre;
        }
    }

    private static final class PreRestoreState {
        final Path dir;
        final Set<String> affectedIds = new LinkedHashSet<>();
        final List<Candidate> candidates = new ArrayList<>();

        PreRestoreState(Path dir) {
            this.dir = dir;
        }

        void addCandidate(String rel, String pre) {
            candidates.add(new Candidate(rel, pre));
        }
    }

    // ---- 路径 ----

    public static Path serverRoot(Path worldDir) {
        Path parent = worldDir.toAbsolutePath().normalize().getParent();
        return parent == null ? worldDir.toAbsolutePath().normalize() : parent;
    }

    public static String rel(Path worldDir, Path path) {
        Path root = serverRoot(worldDir);
        try {
            return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return path.toString().replace('\\', '/');
        }
    }

    private static Path resolve(Path worldDir, String stored) throws IOException {
        if (stored == null || stored.isBlank()) {
            throw new IOException("操作快照缺少路径");
        }
        Path root = serverRoot(worldDir);
        Path raw = Path.of(stored);
        Path path = raw.isAbsolute() ? raw : root.resolve(raw);
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IOException("操作快照路径越界：" + stored);
        }
        return path;
    }

    private static Path manifestFile(Path worldDir) {
        return StackPaths.manifest(worldDir);
    }

    private static Path layerDimensions(Path worldDir, String id) {
        return StackPaths.layerDimensionsFor(worldDir, id);
    }

    private static Path layerMeta(Path worldDir, String id) {
        if (id == null || id.isBlank() || StackPaths.TOP_ID.equals(id)) {
            return StackPaths.samsara(worldDir);
        }
        return StackPaths.stackMetaRoot(worldDir).resolve(id);
    }

    // ---- operation.json ----

    public static Operation beginOperation(Path worldDir, String operation, String jobId,
                                           Path backupDir, StackManifest before,
                                           List<String> affectedIds) {
        Operation op = new Operation();
        op.operation = operation;
        op.jobId = jobId;
        op.id = "ws-" + jobId;
        op.createdAt = System.currentTimeMillis();
        op.beforeManifest = copy(before);
        op.afterManifest = copy(before);
        op.affectedIds = new ArrayList<>(affectedIds == null ? List.of() : affectedIds);
        op.restorable = false;
        op.backupDir = rel(worldDir, backupDir);
        op.notes.add("操作进行中；完成后才可撤销");
        write(worldDir, op);
        return op;
    }

    public static void snapshotTop(Path worldDir, Path backupDir) {
        Path dst = backupDir.resolve("before/top");
        for (String rel : TOP_ITEMS) {
            copyTree(worldDir.resolve(rel), dst.resolve(rel));
        }
        copyFile(manifestFile(worldDir), dst.resolve("stack.json"));
    }

    public static void snapshotLayer(Path worldDir, Path backupDir, String layerId) {
        Path dst = backupDir.resolve("before/layers").resolve(layerId);
        copyTree(layerDimensions(worldDir, layerId), dst.resolve("dimensions"));
        copyTree(layerMeta(worldDir, layerId), dst.resolve("meta"));
    }

    public static void finishOperation(Path worldDir, Path backupDir, Operation op,
                                       StackManifest after, boolean restorable,
                                       List<String> notes, List<Path> addedPlayers) {
        if (op == null) {
            return;
        }
        op.afterManifest = copy(after);
        op.restorable = restorable;
        op.failReason = "";
        op.notes.removeIf(note -> note != null && note.contains("操作进行中"));
        if (notes != null) {
            for (String note : notes) {
                if (note != null && !note.isBlank() && !op.notes.contains(note)) {
                    op.notes.add(note);
                }
            }
        }
        if (addedPlayers != null) {
            Path players = worldDir.resolve("players/data");
            for (Path file : addedPlayers) {
                try {
                    Path rel = players.relativize(file.toAbsolutePath().normalize());
                    String name = "players/data/" + rel.toString().replace('\\', '/');
                    if (!op.addedPlayers.contains(name)) {
                        op.addedPlayers.add(name);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        Set<String> beforeIds = new LinkedHashSet<>();
        Set<String> afterIds = new LinkedHashSet<>();
        for (StackLayer layer : op.beforeManifest.layers) {
            beforeIds.add(layer.id);
        }
        for (StackLayer layer : op.afterManifest.layers) {
            afterIds.add(layer.id);
        }
        for (String id : afterIds) {
            if (!beforeIds.contains(id) && !StackPaths.TOP_ID.equals(id)) {
                op.createdLayerIds.add(id);
            }
        }
        for (String id : beforeIds) {
            if (!afterIds.contains(id) && !StackPaths.TOP_ID.equals(id)) {
                op.removedLayerIds.add(id);
            }
        }
        if ("archive".equals(op.operation)) {
            op.archivePath = rel(worldDir, backupDir);
        }
        write(worldDir, op);
    }

    public static boolean write(Path worldDir, Operation op) {
        try {
            Path record = resolve(worldDir, op.backupDir);
            writeAtomic(record.resolve(FILE_NAME), op);
            return true;
        } catch (Exception e) {
            Samsara.LOGGER.warn("[世界栈] 写入 operation.json 失败: {}", e.getMessage());
            return false;
        }
    }

    public static Operation readOperation(Path worldDir, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            Operation op = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    Operation.class);
            if (op == null) {
                return null;
            }
            normalize(op);
            if (op.backupDir == null || op.backupDir.isBlank()) {
                op.backupDir = rel(worldDir, file.getParent());
            }
            return op;
        } catch (Exception e) {
            return null;
        }
    }

    private static void normalize(Operation op) {
        if (op.format <= 0) op.format = 1;
        if (op.id == null || op.id.isBlank()) {
            op.id = op.jobId == null || op.jobId.isBlank() ? "" : "ws-" + op.jobId;
        }
        if (op.operation == null) op.operation = "";
        if (op.jobId == null) op.jobId = "";
        if (op.affectedIds == null) op.affectedIds = new ArrayList<>();
        if (op.notes == null) op.notes = new ArrayList<>();
        if (op.addedPlayers == null) op.addedPlayers = new ArrayList<>();
        if (op.createdLayerIds == null) op.createdLayerIds = new ArrayList<>();
        if (op.removedLayerIds == null) op.removedLayerIds = new ArrayList<>();
        if (op.failReason == null) op.failReason = "";
        if (op.sourcePath == null) op.sourcePath = "";
        if (op.archivePath == null) op.archivePath = "";
        if (op.beforeManifest == null) op.beforeManifest = new StackManifest();
        if (op.afterManifest == null) op.afterManifest = new StackManifest();
        op.beforeManifest.normalize();
        op.afterManifest.normalize();
    }

    private static StackManifest copy(StackManifest manifest) {
        if (manifest == null) {
            return new StackManifest();
        }
        StackManifest copy = GSON.fromJson(GSON.toJson(manifest), StackManifest.class);
        return copy == null ? new StackManifest() : copy;
    }

    public static List<Operation> all(Path worldDir) {
        Map<String, Operation> seen = new LinkedHashMap<>();
        for (Path root : List.of(WorldStackAdmin.backupRoot(worldDir),
                WorldStackAdmin.archiveRoot(worldDir))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory).sorted()::iterator) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(PRE_RESTORE_PREFIX) || name.startsWith(RESTORE_STAGING_PREFIX)) {
                        continue;
                    }
                    Operation op = readOperation(worldDir, dir.resolve(FILE_NAME));
                    if (op == null || op.id.isBlank()) {
                        continue;
                    }
                    Operation existing = seen.get(op.id);
                    if (existing == null || op.backupDir.contains("world-stack-backup")) {
                        seen.put(op.id, op);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        List<Operation> out = new ArrayList<>(seen.values());
        out.sort(Comparator.comparingLong((Operation op) -> op.createdAt)
                .thenComparing(op -> op.id));
        return out;
    }

    public static Operation find(Path worldDir, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Operation op : all(worldDir)) {
            if (id.equals(op.id) || id.equals("ws-" + op.jobId) || id.equals(op.jobId)) {
                return op;
            }
        }
        return null;
    }

    // ---- 时间线 ----

    public static synchronized Map<String, Object> timeline(Path worldDir) {
        List<Node> nodes = collect(worldDir);
        nodes.sort(Comparator.comparingLong((Node node) -> node.createdAt)
                .thenComparing(node -> node.id == null ? "" : node.id));
        String headId = "";
        // 优先指向最后一个尚未撤销的完整操作；撤销后可继续回退到上一个节点。
        for (Node node : nodes) {
            if (node.op != null && node.op.restoredAt == 0 && node.op.restorable) {
                headId = node.id;
            }
        }
        if (headId.isBlank()) {
            // 全部操作都已撤销：当前状态以最后一次撤销任务为时间线末端。
            for (Node node : nodes) {
                if (node.restore) {
                    headId = node.id;
                }
            }
        }
        if (headId.isBlank()) {
            for (Node node : nodes) {
                if (node.op != null) {
                    headId = node.id;
                }
            }
        }
        if (headId.isBlank() && !nodes.isEmpty()) {
            headId = nodes.get(nodes.size() - 1).id;
        }
        StackManifest current = StackManifest.load(worldDir);
        List<BackupTimeline.Entry> entries = new ArrayList<>();
        String previous = "";
        for (Node node : nodes) {
            BackupTimeline.Entry entry = new BackupTimeline.Entry();
            entry.id = node.id;
            entry.parentId = previous;
            entry.branchId = BRANCH_ID;
            entry.label = operationLabel(node.operation) + " · " + node.jobId;
            entry.createdAt = node.createdAt;
            entry.data.putAll(data(node, current, headId));
            entries.add(entry);
            previous = node.id;
        }
        BackupTimeline.Tree tree = BackupTimeline.build(entries, headId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("branchId", BRANCH_ID);
        out.put("headId", tree.headId);
        out.put("pendingBranch", false);
        out.put("currentSummary", current.summary());
        out.put("nodes", tree.nodes);
        return out;
    }

    public static synchronized String headId(Path worldDir) {
        Object value = timeline(worldDir).get("headId");
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> data(Node node, StackManifest current, String headId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", node.operation);
        data.put("operationLabel", operationLabel(node.operation));
        data.put("jobId", node.jobId);
        data.put("jobType", node.job == null ? node.operation : node.job.type);
        data.put("jobStatus", node.job == null ? "" : node.job.status);
        data.put("jobMessage", node.job == null ? "" : node.job.message);
        data.put("job", node.job);
        data.put("legacy", node.op == null && !node.restore);
        data.put("deletable", false);
        data.put("requiresRestart", true);
        data.put("backupDir", node.dir == null ? "" : node.dir.toAbsolutePath().normalize().toString());
        data.put("currentSummary", current.summary());
        if (node.op != null) {
            data.put("restoredAt", node.op.restoredAt);
            data.put("restoredBy", node.op.restoreJobId);
            data.put("notes", node.op.notes);
            data.put("affectedIds", node.op.affectedIds);
            data.put("beforeManifest", node.op.beforeManifest);
            data.put("afterManifest", node.op.afterManifest);
            data.put("beforeSummary", summary(node.op.beforeManifest));
            data.put("afterSummary", summary(node.op.afterManifest));
            data.put("sourcePath", node.op.sourcePath);
            data.put("archivePath", node.op.archivePath);
            data.put("addedPlayers", node.op.addedPlayers);
        } else if (node.restore && node.job != null) {
            data.put("restoredOperationId", node.job.targetId);
            data.put("affectedIds", node.job.affectedIds == null ? List.of() : node.job.affectedIds);
            data.put("beforeManifest", node.job.beforeManifest);
            data.put("afterManifest", node.job.afterManifest);
            data.put("beforeSummary", summary(node.job.beforeManifest));
            data.put("afterSummary", summary(node.job.afterManifest));
        }
        if (node.restore) {
            data.put("restorable", false);
            data.put("restorableReason", "撤销操作本身不可再撤销；如需继续回退，请撤销更早的节点");
        } else if (node.op == null) {
            data.put("restorable", false);
            data.put("restorableReason", legacyReason(node.operation));
        } else if (node.op.restoredAt > 0) {
            data.put("restorable", false);
            data.put("restorableReason", "该操作已于 " + node.op.restoredAt + " 撤销");
        } else if (!node.op.restorable) {
            data.put("restorable", false);
            data.put("restorableReason", reasonOf(node.op));
        } else if (!node.id.equals(headId)) {
            data.put("restorable", false);
            data.put("restorableReason", "世界栈时间线是线性的，只能撤销末端操作；请先撤销更晚的操作");
        } else if (!same(current, node.op.afterManifest)) {
            data.put("restorable", false);
            data.put("restorableReason", "当前栈清单与该操作完成后的 afterManifest 不一致；"
                    + "可能已有人工改动或后续操作");
        } else {
            data.put("restorable", true);
            data.put("restorableReason", "");
        }
        return data;
    }

    private static List<Node> collect(Path worldDir) {
        List<Node> out = new ArrayList<>();
        Map<String, Node> byJob = new LinkedHashMap<>();
        Map<String, WorldStackAdmin.Job> jobs = new HashMap<>();
        for (WorldStackAdmin.Job job : WorldStackAdmin.jobs(worldDir, 10000)) {
            if (job != null && job.id != null && !job.id.isBlank()) {
                jobs.put(job.id, job);
            }
        }
        for (Operation op : all(worldDir)) {
            Node node = new Node();
            node.id = op.id;
            node.jobId = op.jobId;
            node.operation = op.operation;
            node.createdAt = op.createdAt;
            node.op = op;
            node.job = jobs.get(op.jobId);
            node.dir = dirOf(worldDir, op);
            byJob.put(op.jobId, node);
            out.add(node);
        }
        Path backupRoot = WorldStackAdmin.backupRoot(worldDir);
        if (Files.isDirectory(backupRoot)) {
            try (Stream<Path> stream = Files.list(backupRoot)) {
                for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                    if (!Files.isDirectory(dir)) continue;
                    String name = dir.getFileName().toString();
                    String type = name.startsWith("import-") ? "import"
                            : name.startsWith("promote-") ? "promote" : "";
                    if (type.isEmpty() || Files.isRegularFile(dir.resolve(FILE_NAME))) continue;
                    String jobId = name.substring(type.length() + 1);
                    if (byJob.containsKey(jobId)) continue;
                    Node node = new Node();
                    node.jobId = jobId;
                    node.id = "ws-" + jobId;
                    node.operation = type;
                    node.job = jobs.get(jobId);
                    node.dir = dir;
                    node.createdAt = node.job != null && node.job.createdAt > 0
                            ? node.job.createdAt : mtime(dir);
                    byJob.put(jobId, node);
                    out.add(node);
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[世界栈] 读取时间线备份目录失败: {}", e.getMessage());
            }
        }
        Path archiveRoot = WorldStackAdmin.archiveRoot(worldDir);
        if (Files.isDirectory(archiveRoot)) {
            try (Stream<Path> stream = Files.list(archiveRoot)) {
                for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                    if (!Files.isDirectory(dir) || Files.isRegularFile(dir.resolve(FILE_NAME))) {
                        continue;
                    }
                    String jobId = archiveJobId(dir.getFileName().toString(), jobs);
                    if (jobId.isBlank() || byJob.containsKey(jobId)) continue;
                    Node node = new Node();
                    node.jobId = jobId;
                    node.id = "ws-" + jobId;
                    node.operation = "archive";
                    node.job = jobs.get(jobId);
                    node.dir = dir;
                    node.createdAt = node.job != null && node.job.createdAt > 0
                            ? node.job.createdAt : mtime(dir);
                    byJob.put(jobId, node);
                    out.add(node);
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[世界栈] 读取时间线归档目录失败: {}", e.getMessage());
            }
        }
        for (WorldStackAdmin.Job job : jobs.values()) {
            if (job == null || job.id == null || job.id.isBlank() || byJob.containsKey(job.id)) {
                continue;
            }
            Node node = new Node();
            node.jobId = job.id;
            node.id = "ws-" + job.id;
            node.operation = job.type;
            node.job = job;
            node.createdAt = job.createdAt;
            if ("restore".equals(job.type)) {
                node.restore = true;
            } else if ("import".equals(job.type) || "promote".equals(job.type)
                    || "archive".equals(job.type)) {
                node.dir = findBackupDir(worldDir, job.type, job.id);
            } else {
                continue;
            }
            byJob.put(job.id, node);
            out.add(node);
        }
        return out;
    }

    private static Path dirOf(Path worldDir, Operation op) {
        if (op.archivePath != null && !op.archivePath.isBlank()) {
            try {
                Path path = resolve(worldDir, op.archivePath);
                if (Files.isDirectory(path)) return path;
            } catch (Exception ignored) {
            }
        }
        if (op.backupDir != null && !op.backupDir.isBlank()) {
            try {
                return resolve(worldDir, op.backupDir);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Path findBackupDir(Path worldDir, String type, String jobId) {
        if ("import".equals(type)) {
            Path dir = WorldStackAdmin.backupRoot(worldDir).resolve("import-" + jobId);
            return Files.isDirectory(dir) ? dir : null;
        }
        if ("promote".equals(type)) {
            Path dir = WorldStackAdmin.backupRoot(worldDir).resolve("promote-" + jobId);
            return Files.isDirectory(dir) ? dir : null;
        }
        if ("archive".equals(type)) {
            Path root = WorldStackAdmin.archiveRoot(worldDir);
            if (Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.list(root)) {
                    for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                        if (Files.isDirectory(dir)
                                && dir.getFileName().toString().endsWith("-" + jobId)) {
                            return dir;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static String archiveJobId(String dirName, Map<String, WorldStackAdmin.Job> jobs) {
        for (String id : jobs.keySet()) {
            if (id != null && dirName.endsWith("-" + id)) return id;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(.*)-(arc-\\d+-[0-9a-fA-F]+)$").matcher(dirName);
        return matcher.matches() ? matcher.group(2) : "";
    }

    // ---- 预览 / 排队 ----

    public static Map<String, Object> preview(Path worldDir, String id) {
        return GSON.fromJson(GSON.toJson(previewRestore(worldDir, id)),
                new TypeToken<Map<String, Object>>() { }.getType());
    }

    private static Preview previewRestore(Path worldDir, String id) {
        Preview preview = new Preview();
        preview.id = id == null ? "" : id.trim();
        Operation op = find(worldDir, preview.id);
        preview.legacy = op == null;
        if (op == null) {
            preview.errors.add("该节点是旧备份或任务报告，没有 operation.json；"
                    + "旧版本备份缺少操作前栈清单与撤销所需的目录/玩家文件记录，"
                    + "只能只读展示，不能撤销");
            return preview;
        }
        preview.operation = op.operation;
        preview.jobId = op.jobId;
        preview.backupDir = op.backupDir;
        preview.createdAt = op.createdAt;
        preview.affectedIds.addAll(op.affectedIds);
        preview.beforeLayers.addAll(briefs(op.beforeManifest));
        preview.afterLayers.addAll(briefs(op.afterManifest));
        preview.beforeSummary.addAll(summary(op.beforeManifest));
        preview.afterSummary.addAll(summary(op.afterManifest));
        List<String> errors = validate(worldDir, op);
        preview.errors.addAll(errors);
        preview.restorable = errors.isEmpty();
        preview.reason = errors.isEmpty() ? "" : errors.get(0);
        try {
            Path dir = dirOf(worldDir, op);
            preview.backupBytes = dir == null ? 0 : sizeOf(dir);
            preview.currentBytes = currentAffectedBytes(worldDir, op);
            preview.freeBytes = freeBytes(worldDir);
            preview.neededBytes = restoreBytes(worldDir, op) + MIN_FREE_MARGIN;
            if (preview.freeBytes >= 0 && preview.freeBytes < preview.neededBytes) {
                preview.errors.add("磁盘空间不足：撤销约需 " + human(preview.neededBytes)
                        + "，当前可用 " + human(preview.freeBytes));
            }
        } catch (Exception e) {
            preview.errors.add("读取备份大小失败：" + e.getMessage());
        }
        preview.steps.add("停服后、下次启动前执行；不会在运行中的服务器上搬目录");
        preview.steps.add("先把当前受影响的栈层/顶层/playerdata 移到 "
                + PRE_RESTORE_PREFIX + "<job>/，失败可回滚");
        switch (op.operation) {
            case "import" -> {
                preview.steps.add("移除本次导入新增的栈层目录与清单项");
                if (topChanged(op)) {
                    preview.steps.add("用 before/top 快照恢复操作前的旧栈顶 world");
                }
                preview.steps.add("按 beforeManifest 写回 stack.json；源目录保持不变");
            }
            case "promote" -> {
                preview.steps.add("把当前 world 与被提升层的数据交换回操作前位置");
                preview.steps.add("按 before/top 与 before/layers/<id> 快照恢复顶层和层顺序");
            }
            case "archive" -> {
                preview.steps.add("从归档目录复制回该层的 dimensions/meta（归档保留不删除）");
                preview.steps.add("按 beforeManifest 把该层写回 stack.json");
            }
            default -> {
            }
        }
        preview.steps.add("写回 stack.json 并同步各旧栈层自己的 stack.json");
        preview.steps.add("成功后保留 pre-restore 供人工核对；失败自动回滚");
        preview.warnings.add("撤销会丢失该操作之后对相关栈层的运行期修改；"
                + "当前状态先保存到 pre-restore");
        preview.warnings.add("世界栈时间线是线性的；仅支持撤销末端节点，不支持分支");
        preview.warnings.add("源世界、归档与用户备份永不自动删除");
        preview.warnings.add("必须停服后、下次启动前执行；运行中排队不会立即回滚");
        if (!op.addedPlayers.isEmpty()) {
            preview.warnings.add("本次导入新增 " + op.addedPlayers.size()
                    + " 个玩家数据文件；撤销会移走它们（当前内容先保留到 pre-restore）");
        }
        preview.ok = preview.errors.isEmpty();
        return preview;
    }

    public static Map<String, Object> schedule(Path worldDir, String id, boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Preview preview = previewRestore(worldDir, id);
        if (!confirmed) {
            out.put("ok", false);
            out.put("error", "撤销需要二次确认（confirm=true）");
            out.put("preview", preview);
            return out;
        }
        if (!preview.ok) {
            out.put("ok", false);
            out.put("error", preview.errors.isEmpty() ? "撤销预览未通过" : preview.errors.get(0));
            out.put("preview", preview);
            return out;
        }
        if (hasPending(worldDir)) {
            out.put("ok", false);
            out.put("error", "已有待执行的栈任务或撤销任务；请先重启完成");
            out.put("preview", preview);
            return out;
        }
        if (WorldStackAdmin.serverBackupPending(worldDir)) {
            out.put("ok", false);
            out.put("error", "已有待执行的本体备份/恢复任务；请先重启完成再撤销世界栈操作");
            out.put("preview", preview);
            return out;
        }
        PendingRestore pending = new PendingRestore();
        pending.id = "wsr-" + System.currentTimeMillis();
        pending.jobId = WorldStackAdmin.newJobId("wsrestore");
        pending.operationId = preview.id;
        pending.backupDir = preview.backupDir;
        pending.createdAt = System.currentTimeMillis();
        pending.confirm = true;
        if (!WorldStackAdmin.writePending(worldDir, PENDING_RESTORE, pending)) {
            out.put("ok", false);
            out.put("error", "无法写入 pending-stack-restore.json");
            return out;
        }
        WorldStackAdmin.Job job = WorldStackAdmin.newJob(pending.jobId, "restore", preview.id);
        job.requiresRestart = true;
        job.message = "已排队：停服后、下次启动前撤销操作 " + preview.id;
        WorldStackAdmin.writeJob(worldDir, job);
        out.put("ok", true);
        out.put("requiresRestart", true);
        out.put("jobId", pending.jobId);
        out.put("id", pending.id);
        out.put("operationId", preview.id);
        out.put("job", job);
        out.put("preview", preview);
        return out;
    }

    public static boolean hasPending(Path worldDir) {
        Path samsara = worldDir.resolve("samsara");
        return Files.isRegularFile(samsara.resolve(PENDING_RESTORE))
                || Files.isRegularFile(samsara.resolve(WorldStackAdmin.PENDING_IMPORT))
                || Files.isRegularFile(samsara.resolve(WorldStackAdmin.PENDING_IMPORTS))
                || Files.isRegularFile(samsara.resolve(WorldStackAdmin.PENDING_ARCHIVE))
                || Files.isRegularFile(samsara.resolve(WorldStackAdmin.PENDING_PROMOTE))
                || Files.isRegularFile(samsara.resolve("pending-new.json"));
    }

    // ---- 导出 / 快照清理 ----

    /** 导出节点对应的 operation.json（含前后完整 stack.json 与 job），供服主离线排障。 */
    public static Map<String, Object> export(Path worldDir, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        Operation op = find(worldDir, id);
        if (op == null) {
            out.put("ok", false);
            out.put("error", "该节点没有 operation.json（旧备份或撤销任务），不能导出操作快照");
            return out;
        }
        WorldStackAdmin.Job job = null;
        for (WorldStackAdmin.Job candidate : WorldStackAdmin.jobs(worldDir, 10000)) {
            if (candidate != null && op.jobId.equals(candidate.id)) {
                job = candidate;
                break;
            }
        }
        out.put("ok", true);
        out.put("exportVersion", 1);
        out.put("generatedAt", System.currentTimeMillis());
        out.put("id", op.id);
        out.put("jobId", op.jobId);
        out.put("operation", op.operation);
        out.put("createdAt", op.createdAt);
        out.put("backupDir", op.backupDir);
        out.put("restorable", op.restorable);
        out.put("failReason", op.failReason);
        out.put("restoredAt", op.restoredAt);
        out.put("restoreJobId", op.restoreJobId);
        out.put("affectedIds", op.affectedIds);
        out.put("notes", op.notes);
        out.put("sourcePath", op.sourcePath);
        out.put("archivePath", op.archivePath);
        out.put("addedPlayers", op.addedPlayers);
        out.put("beforeManifest", op.beforeManifest);
        out.put("afterManifest", op.afterManifest);
        out.put("beforeSummary", summary(op.beforeManifest));
        out.put("afterSummary", summary(op.afterManifest));
        out.put("job", job);
        return out;
    }

    /** 列出撤销前安全快照；这些目录永不自动清理，只能由服主手动删除。 */
    public static Map<String, Object> snapshots(Path worldDir) {
        Path root = WorldStackAdmin.backupRoot(worldDir);
        List<Map<String, Object>> list = new ArrayList<>();
        long total = 0L;
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                for (Path dir : (Iterable<Path>) stream.sorted()::iterator) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    String name = dir.getFileName().toString();
                    if (!name.startsWith(PRE_RESTORE_PREFIX)) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("path", dir.toAbsolutePath().normalize().toString());
                    long bytes = sizeOf(dir);
                    item.put("bytes", bytes);
                    item.put("modifiedAt", mtime(dir));
                    total += bytes;
                    Path state = dir.resolve("state.json");
                    if (Files.isRegularFile(state)) {
                        try {
                            Map<String, Object> saved = GSON.fromJson(
                                    Files.readString(state, StandardCharsets.UTF_8),
                                    new TypeToken<Map<String, Object>>() { }.getType());
                            if (saved != null) {
                                item.put("restoreJobId", saved.get("restoreJobId"));
                                item.put("operationId", saved.get("operationId"));
                                item.put("operation", saved.get("operation"));
                                item.put("createdAt", saved.get("createdAt"));
                                item.put("currentSummary", saved.get("currentSummary"));
                                item.put("affectedIds", saved.get("affectedIds"));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    list.add(item);
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[世界栈] 读取 pre-restore 快照失败: {}", e.getMessage());
            }
        }
        list.sort(Comparator.comparingLong((Map<String, Object> item) -> {
            Object value = item.get("modifiedAt");
            return value instanceof Number number ? number.longValue() : 0L;
        }).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("root", root.toAbsolutePath().normalize().toString());
        out.put("totalBytes", total);
        out.put("snapshots", list);
        return out;
    }

    /** 手动删除一个 pre-restore 安全快照；仅允许固定前缀，禁止路径穿越。 */
    public static Map<String, Object> cleanup(Path worldDir, String name, boolean confirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!confirmed) {
            out.put("ok", false);
            out.put("error", "删除快照需要二次确认");
            return out;
        }
        if (name == null || name.isBlank() || !name.startsWith(PRE_RESTORE_PREFIX)
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            out.put("ok", false);
            out.put("error", "只允许删除 pre-restore-* 安全快照");
            return out;
        }
        Path root = WorldStackAdmin.backupRoot(worldDir).toAbsolutePath().normalize();
        Path dir = root.resolve(name).normalize();
        if (!root.equals(dir.getParent())) {
            out.put("ok", false);
            out.put("error", "快照路径越界");
            return out;
        }
        if (!Files.isDirectory(dir)) {
            out.put("ok", false);
            out.put("error", "快照不存在：" + name);
            return out;
        }
        long freed = sizeOf(dir);
        deleteRecursive(dir);
        if (Files.exists(dir)) {
            out.put("ok", false);
            out.put("error", "删除失败：" + name);
            return out;
        }
        out.put("ok", true);
        out.put("name", name);
        out.put("freedBytes", freed);
        out.put("message", "已删除安全快照 " + name + "（释放 " + human(freed) + "）");
        return out;
    }

    // ---- 启动消费 / 撤销执行 ----

    public static synchronized void consumePendingRestore(Path worldDir) {
        Path file = worldDir.resolve("samsara").resolve(PENDING_RESTORE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        PendingRestore pending = WorldStackAdmin.readPending(worldDir, PENDING_RESTORE,
                PendingRestore.class);
        if (pending == null || pending.jobId == null || pending.jobId.isBlank()
                || pending.operationId == null || pending.operationId.isBlank()) {
            WorldStackAdmin.Job job = WorldStackAdmin.newJob(
                    "wsrestore-bad-" + System.currentTimeMillis(), "restore", "");
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "pending-stack-restore.json 解析失败或缺少 jobId/operationId";
            WorldStackAdmin.writeJob(worldDir, job);
            WorldStackAdmin.moveFailedFile(worldDir, PENDING_RESTORE, file);
            return;
        }
        WorldStackAdmin.Job job = WorldStackAdmin.readJob(worldDir, pending.jobId);
        if (job == null) {
            job = WorldStackAdmin.newJob(pending.jobId, "restore", pending.operationId);
        }
        boolean conflict;
        Path samsara = worldDir.resolve("samsara");
        conflict = Files.exists(samsara.resolve(WorldStackAdmin.PENDING_IMPORT))
                || Files.exists(samsara.resolve(WorldStackAdmin.PENDING_IMPORTS))
                || Files.exists(samsara.resolve(WorldStackAdmin.PENDING_ARCHIVE))
                || Files.exists(samsara.resolve(WorldStackAdmin.PENDING_PROMOTE))
                || Files.isRegularFile(samsara.resolve("pending-new.json"));
        if (conflict) {
            fail(worldDir, job, file, "同时存在导入/归档/提升/新世界 pending 任务，拒绝自动执行栈撤销");
            return;
        }
        Preview preview = previewRestore(worldDir, pending.operationId);
        if (!preview.ok) {
            fail(worldDir, job, file, "撤销校验失败："
                    + (preview.errors.isEmpty() ? "预览未通过" : String.join("；", preview.errors)));
            return;
        }
        executeRestore(worldDir, pending, preview, job);
    }

    private static void executeRestore(Path worldDir, PendingRestore pending, Preview preview,
                                       WorldStackAdmin.Job job) {
        Path file = worldDir.resolve("samsara").resolve(PENDING_RESTORE);
        Operation op = find(worldDir, pending.operationId);
        Path preRoot = WorldStackAdmin.backupRoot(worldDir).resolve(PRE_RESTORE_PREFIX + pending.jobId);
        PreRestoreState state = null;
        try {
            if (op == null) {
                throw new IOException("操作快照不存在：" + pending.operationId);
            }
            StackManifest current = StackManifest.load(worldDir);
            job.status = "running";
            job.stage = "校验操作前状态";
            job.startedAt = System.currentTimeMillis();
            job.operation = "restore";
            job.targetId = op.id;
            job.beforeManifest = copy(current);
            job.affectedIds = new ArrayList<>(affectedIds(op));
            WorldStackAdmin.writeJob(worldDir, job);

            deleteRecursive(preRoot);
            Files.createDirectories(preRoot);
            state = new PreRestoreState(preRoot);
            state.affectedIds.addAll(affectedIds(op));
            job.stage = "保留当前栈状态";
            WorldStackAdmin.writeJob(worldDir, job);
            capturePreRestore(worldDir, state, op);
            job.log.add("pre-restore=" + preRoot);

            job.stage = "恢复操作前栈结构与数据";
            WorldStackAdmin.writeJob(worldDir, job);
            applyRestore(worldDir, op);

            StackManifest restored = StackManifest.load(worldDir);
            job.afterManifest = copy(restored);
            if (!same(restored, op.beforeManifest)) {
                throw new IOException("撤销后 stack.json 与 beforeManifest 不一致");
            }
            op.restoredAt = System.currentTimeMillis();
            op.restoreJobId = pending.jobId;
            op.restorable = false;
            op.notes.add("已于 " + op.restoredAt + " 撤销；撤销任务 " + pending.jobId
                    + "，当前状态保留在 " + rel(worldDir, preRoot));
            write(worldDir, op);

            Files.deleteIfExists(file);
            job.status = "done";
            job.stage = "完成";
            job.progress = 100;
            job.finishedAt = System.currentTimeMillis();
            job.message = "已撤销 " + op.id + "（" + op.operation + "）；当前栈："
                    + restored.summary();
            job.log.add("恢复后 stack.json=" + restored.summary());
            job.log.add("撤销前的栈状态保留在 " + preRoot);
            job.log.add("源世界、归档与用户备份未删除");
            WorldStackAdmin.writeJob(worldDir, job);
            Samsara.LOGGER.info("[世界栈] {}", job.message);
        } catch (Exception e) {
            if (state != null) {
                try {
                    rollbackPreRestore(worldDir, state);
                    job.log.add("已按 pre-restore 回滚到执行前状态");
                } catch (Exception rollback) {
                    job.log.add("撤销回滚失败：" + rollback.getMessage());
                    Samsara.LOGGER.error("[世界栈] 撤销回滚失败: {}", rollback.getMessage());
                }
            }
            job.status = "failed";
            job.stage = "失败";
            job.finishedAt = System.currentTimeMillis();
            job.message = "撤销失败：" + e.getMessage();
            job.log.add(job.message);
            WorldStackAdmin.writeJob(worldDir, job);
            WorldStackAdmin.moveFailedFile(worldDir, PENDING_RESTORE, file);
            Samsara.LOGGER.error("[世界栈] {}", job.message);
        }
    }

    private static void capturePreRestore(Path worldDir, PreRestoreState state, Operation op) {
        Path pre = state.dir;
        copyFile(manifestFile(worldDir), pre.resolve("stack.json"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("format", 1);
        info.put("restoreJobId", pre.getFileName().toString()
                .substring(PRE_RESTORE_PREFIX.length()));
        info.put("operationId", op.id);
        info.put("operation", op.operation);
        info.put("createdAt", System.currentTimeMillis());
        info.put("currentSummary", StackManifest.load(worldDir).summary());
        info.put("beforeSummary", summary(op.beforeManifest));
        info.put("afterSummary", summary(op.afterManifest));
        info.put("affectedIds", affectedIds(op));
        try {
            writeAtomic(pre.resolve("state.json"), info);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 写入 pre-restore state.json 失败: {}", e.getMessage());
        }
        if (state.affectedIds.contains(StackPaths.TOP_ID)) {
            for (String rel : TOP_ITEMS) {
                state.addCandidate(rel, "top/" + rel);
            }
        }
        for (String id : state.affectedIds) {
            if (StackPaths.TOP_ID.equals(id)) {
                continue;
            }
            state.addCandidate("dimensions/samsara/" + id, "layers/" + id + "/dimensions");
            state.addCandidate("samsara/stack/" + id, "layers/" + id + "/meta");
        }
        for (String rel : op.addedPlayers) {
            if (rel == null || rel.isBlank() || rel.contains("..")) {
                continue;
            }
            state.addCandidate(rel, "players/" + rel.replace("players/data/", ""));
        }
        for (Candidate candidate : state.candidates) {
            moveIfExists(worldDir.resolve(candidate.rel), pre.resolve(candidate.pre));
        }
    }

    private static void applyRestore(Path worldDir, Operation op) throws IOException {
        Path backup = dirOf(worldDir, op);
        if (backup == null) {
            throw new IOException("备份目录不存在：" + op.backupDir);
        }
        String operation = op.operation == null ? "" : op.operation;
        switch (operation) {
            case "import" -> {
                if (topChanged(op)) {
                    restoreTop(worldDir, backup.resolve("before/top"));
                }
            }
            case "promote" -> {
                restoreTop(worldDir, backup.resolve("before/top"));
                for (String id : affectedIds(op)) {
                    if (!StackPaths.TOP_ID.equals(id)) {
                        restoreLayer(worldDir, backup.resolve("before/layers").resolve(id), id);
                    }
                }
            }
            case "archive" -> {
                Path archive = archivePath(worldDir, op);
                if (archive == null || !Files.isDirectory(archive)) {
                    throw new IOException("归档目录不存在");
                }
                for (String id : affectedIds(op)) {
                    if (StackPaths.TOP_ID.equals(id)) continue;
                    copyTree(archive.resolve("dimensions"), layerDimensions(worldDir, id));
                    copyTree(archive.resolve("meta"), layerMeta(worldDir, id));
                }
            }
            default -> throw new IOException("未知操作类型：" + operation);
        }
        writeManifest(worldDir, op.beforeManifest);
    }

    private static Path archivePath(Path worldDir, Operation op) throws IOException {
        if (op.archivePath != null && !op.archivePath.isBlank()) {
            return resolve(worldDir, op.archivePath);
        }
        return dirOf(worldDir, op);
    }

    private static void restoreTop(Path worldDir, Path snapshot) throws IOException {
        if (!Files.isDirectory(snapshot)) {
            throw new IOException("缺少操作前顶层快照：" + snapshot);
        }
        for (String rel : TOP_ITEMS) {
            copyTree(snapshot.resolve(rel), worldDir.resolve(rel));
        }
    }

    private static void restoreLayer(Path worldDir, Path snapshot, String id) throws IOException {
        if (!Files.isDirectory(snapshot)) {
            throw new IOException("缺少栈层 " + id + " 的操作前快照");
        }
        copyTree(snapshot.resolve("dimensions"), layerDimensions(worldDir, id));
        copyTree(snapshot.resolve("meta"), layerMeta(worldDir, id));
    }

    private static void rollbackPreRestore(Path worldDir, PreRestoreState state) throws IOException {
        for (Candidate candidate : state.candidates) {
            Path current = worldDir.resolve(candidate.rel);
            deleteRecursive(current);
            Path saved = state.dir.resolve(candidate.pre);
            if (Files.exists(saved)) {
                Files.createDirectories(current.getParent());
                moveIfExists(saved, current);
            }
        }
        Path savedManifest = state.dir.resolve("stack.json");
        if (Files.isRegularFile(savedManifest)) {
            copyFile(savedManifest, manifestFile(worldDir));
        }
    }

    private static void writeManifest(Path worldDir, StackManifest manifest) throws IOException {
        manifest.normalize();
        Path file = manifestFile(worldDir);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(manifest), StandardCharsets.UTF_8);
        moveIfExists(tmp, file);
        for (StackLayer layer : manifest.layers) {
            if (layer.top) continue;
            Path meta = StackPaths.layerStackFile(worldDir, layer);
            Files.createDirectories(meta.getParent());
            Files.writeString(meta, GSON.toJson(layer), StandardCharsets.UTF_8);
        }
    }

    private static void fail(Path worldDir, WorldStackAdmin.Job job, Path pendingFile, String message) {
        job.status = "failed";
        job.stage = "失败";
        job.finishedAt = System.currentTimeMillis();
        job.message = message;
        job.log.add(message);
        WorldStackAdmin.writeJob(worldDir, job);
        WorldStackAdmin.moveFailedFile(worldDir, PENDING_RESTORE, pendingFile);
        Samsara.LOGGER.error("[世界栈] {}", message);
    }

    // ---- 校验 ----

    private static List<String> validate(Path worldDir, Operation op) {
        List<String> errors = new ArrayList<>();
        if (op.format > 1) {
            errors.add("operation.json 格式版本 " + op.format + " 高于当前支持的 1");
        }
        if (!op.restorable) {
            errors.add(reasonOf(op));
        }
        if (op.restoredAt > 0) {
            errors.add("该操作已经撤销过（" + op.restoredAt + "）");
        }
        if (!headId(worldDir).equals(op.id)) {
            errors.add("只能撤销时间线末端的操作；请先撤销更晚的操作（世界栈时间线不支持分支）");
        }
        StackManifest current = StackManifest.load(worldDir);
        if (!same(current, op.afterManifest)) {
            errors.add("当前栈清单与该操作完成后的 afterManifest 不一致；"
                    + "可能已有人工改动或后续操作，拒绝撤销");
        }
        validateBackup(worldDir, op, errors);
        return errors;
    }

    private static void validateBackup(Path worldDir, Operation op, List<String> errors) {
        try {
            switch (op.operation == null ? "" : op.operation) {
                case "import" -> {
                    if (topChanged(op)) {
                        Path top = dirOf(worldDir, op).resolve("before/top");
                        if (!Files.isRegularFile(top.resolve("level.dat"))) {
                            errors.add("快照缺少 before/top/level.dat，无法恢复操作前栈顶");
                        }
                        if (!Files.isDirectory(top.resolve("dimensions/minecraft"))) {
                            errors.add("快照缺少 before/top/dimensions/minecraft，无法恢复操作前顶层");
                        }
                    }
                }
                case "promote" -> {
                    Path backup = dirOf(worldDir, op);
                    if (!Files.isRegularFile(backup.resolve("before/top/level.dat"))) {
                        errors.add("快照缺少 before/top/level.dat，无法恢复操作前栈顶");
                    }
                    for (String id : affectedIds(op)) {
                        if (StackPaths.TOP_ID.equals(id)) continue;
                        Path layer = backup.resolve("before/layers").resolve(id);
                        if (!Files.isDirectory(layer.resolve("dimensions"))
                                || !Files.isDirectory(layer.resolve("meta"))) {
                            errors.add("快照缺少 before/layers/" + id + " 的 dimensions/meta");
                        }
                    }
                }
                case "archive" -> {
                    Path archive = archivePath(worldDir, op);
                    if (archive == null || !Files.isDirectory(archive)) {
                        errors.add("归档目录不存在");
                    } else if (!Files.isDirectory(archive.resolve("dimensions"))
                            && !Files.isDirectory(archive.resolve("meta"))) {
                        errors.add("归档目录缺少 dimensions/meta，无法恢复栈层");
                    }
                }
                default -> errors.add("不支持撤销的操作类型：" + op.operation);
            }
        } catch (Exception e) {
            errors.add("校验操作快照路径失败：" + e.getMessage());
        }
    }

    private static boolean topChanged(Operation op) {
        StackManifest before = op.beforeManifest;
        StackManifest after = op.afterManifest;
        return before != null && after != null
                && !topSignature(before).equals(topSignature(after));
    }

    private static List<String> affectedIds(Operation op) {
        Set<String> ids = new LinkedHashSet<>(op.affectedIds);
        StackManifest before = op.beforeManifest;
        StackManifest after = op.afterManifest;
        if (before != null && after != null) {
            Set<String> beforeIds = new LinkedHashSet<>();
            Set<String> afterIds = new LinkedHashSet<>();
            for (StackLayer layer : before.layers) beforeIds.add(layer.id);
            for (StackLayer layer : after.layers) afterIds.add(layer.id);
            for (String id : afterIds) if (!beforeIds.contains(id)) ids.add(id);
            for (String id : beforeIds) if (!afterIds.contains(id)) ids.add(id);
            if (!topSignature(before).equals(topSignature(after))) ids.add(StackPaths.TOP_ID);
        }
        return new ArrayList<>(ids);
    }

    private static long restoreBytes(Path worldDir, Operation op) {
        Path dir = dirOf(worldDir, op);
        if (dir == null) return 0;
        return switch (op.operation == null ? "" : op.operation) {
            case "import" -> topChanged(op) ? sizeOf(dir.resolve("before/top")) : 0L;
            case "promote" -> sizeOf(dir.resolve("before"));
            case "archive" -> {
                try {
                    Path archive = archivePath(worldDir, op);
                    yield archive == null ? 0L : sizeOf(archive);
                } catch (IOException e) {
                    yield 0L;
                }
            }
            default -> 0L;
        };
    }

    /** 撤销前需要保留到 pre-restore 的当前数据量（只算受影响路径，不扫描整个存档）。 */
    private static long currentAffectedBytes(Path worldDir, Operation op) {
        long total = 0L;
        Set<String> affected = new LinkedHashSet<>(affectedIds(op));
        if (affected.contains(StackPaths.TOP_ID)) {
            for (String rel : TOP_ITEMS) {
                total += sizeOf(worldDir.resolve(rel));
            }
        }
        for (String id : affected) {
            if (StackPaths.TOP_ID.equals(id)) {
                continue;
            }
            total += sizeOf(layerDimensions(worldDir, id));
            total += sizeOf(layerMeta(worldDir, id));
        }
        for (String rel : op.addedPlayers) {
            if (rel != null && !rel.isBlank()) {
                total += sizeOf(worldDir.resolve(rel));
            }
        }
        return total;
    }

    private static String reasonOf(Operation op) {
        if (op.failReason != null && !op.failReason.isBlank()) return op.failReason;
        if (op.notes != null && !op.notes.isEmpty()) return String.join("；", op.notes);
        return "operation.json 标记为不可撤销，但未写原因";
    }

    private static String legacyReason(String operation) {
        return switch (operation == null ? "" : operation) {
            case "import" -> "旧导入备份缺少 operation.json 与完整撤销快照：只保存了旧栈顶 level.dat 与 "
                    + "data-minecraft，缺少 dimensions/minecraft、claims/diff/track、spawn-history "
                    + "和被降级层的完整记录，不能安全撤销；只读展示";
            case "promote" -> "旧提升备份缺少 operation.json 与完整撤销快照：只保存了部分顶层文件与目标层 "
                    + "data，缺少完整层顺序、dimensions/meta 与 claims/diff/track 记录，不能安全撤销；只读展示";
            case "archive" -> "旧归档缺少 operation.json 与撤销清单，不能判断操作前后层结构；只读展示";
            default -> "旧备份缺少 operation.json 与完整撤销快照，不能安全撤销；只读展示";
        };
    }

    // ---- 展示辅助 ----

    private static String operationLabel(String operation) {
        return switch (operation == null ? "" : operation) {
            case "import" -> "导入";
            case "promote" -> "提升栈顶";
            case "archive" -> "归档";
            case "restore" -> "撤销";
            default -> "未知操作";
        };
    }

    private static List<String> summary(StackManifest manifest) {
        List<String> out = new ArrayList<>();
        if (manifest == null || manifest.layers == null || manifest.layers.isEmpty()) {
            out.add("没有栈层");
            return out;
        }
        out.add(manifest.summary());
        for (StackLayer layer : manifest.layers) {
            out.add("#" + (layer.index + 1) + " " + layer.id
                    + (layer.top ? "（栈顶）" : "")
                    + " · seed=" + layer.seed
                    + " · spawn=" + layer.spawnX + "," + layer.spawnY + "," + layer.spawnZ);
        }
        return out;
    }

    private static List<Map<String, Object>> briefs(StackManifest manifest) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (manifest == null) return out;
        for (StackLayer layer : manifest.layers) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", layer.id);
            map.put("index", layer.index);
            map.put("top", layer.top);
            map.put("seed", String.valueOf(layer.seed));
            map.put("spawnX", layer.spawnX);
            map.put("spawnY", layer.spawnY);
            map.put("spawnZ", layer.spawnZ);
            map.put("source", layer.source);
            out.add(map);
        }
        return out;
    }

    private static boolean same(StackManifest left, StackManifest right) {
        return signature(left).equals(signature(right));
    }

    private static String signature(StackManifest manifest) {
        if (manifest == null) return "none";
        StringBuilder sb = new StringBuilder("v").append(manifest.schemaVersion);
        for (StackLayer layer : manifest.layers) {
            sb.append('|').append(layer.index).append(',')
                    .append(layer.id).append(',')
                    .append(layer.top).append(',')
                    .append(layer.parent).append(',')
                    .append(layer.worldId).append(',')
                    .append(layer.seed).append(',')
                    .append(layer.levelType).append(',')
                    .append(layer.generatorSettings).append(',')
                    .append(layer.spawnX).append(',').append(layer.spawnY).append(',')
                    .append(layer.spawnZ).append(',')
                    .append(layer.source).append(',')
                    .append(layer.importedFrom);
        }
        return sb.toString();
    }

    private static String topSignature(StackManifest manifest) {
        StackLayer top = manifest == null ? null : manifest.top();
        if (top == null) return "none";
        return top.id + "," + top.worldId + "," + top.seed + "," + top.levelType + ","
                + top.generatorSettings + "," + top.spawnX + "," + top.spawnY + ","
                + top.spawnZ + "," + top.source + "," + top.importedFrom;
    }

    // ---- 文件工具 ----

    private static void copyTree(Path source, Path target) {
        if (source == null || !Files.exists(source)) return;
        try {
            if (Files.isDirectory(source)) {
                try (Stream<Path> stream = Files.walk(source)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        Path rel = source.relativize(file);
                        Path dst = target.resolve(rel.toString());
                        if (Files.isDirectory(file)) {
                            Files.createDirectories(dst);
                        } else {
                            Files.createDirectories(dst.getParent());
                            Files.copy(file, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("复制快照失败 " + source + " -> " + target + ": "
                    + e.getMessage(), e);
        }
    }

    private static void copyFile(Path source, Path target) {
        if (source == null || !Files.isRegularFile(source)) return;
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("复制文件失败 " + source + " -> " + target + ": "
                    + e.getMessage(), e);
        }
    }

    private static void moveIfExists(Path source, Path target) {
        if (source == null || !Files.exists(source)) return;
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("移动失败 " + source + " -> " + target + ": "
                    + e.getMessage(), e);
        }
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path)) return;
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
        if (path == null || !Files.exists(path)) return 0L;
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long freeBytes(Path worldDir) {
        try {
            return Files.getFileStore(worldDir).getUsableSpace();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
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
}
