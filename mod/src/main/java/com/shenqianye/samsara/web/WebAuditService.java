package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.storage.StoragePaths;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Web 修改审计：在任何管理端写请求之前快照 Web 可管理的文件，之后可随时回滚。
 *
 * <p>覆盖范围刻意限制在“Web 自己会写的小文件”：{@code config/samsara/}、六个服务器
 * 配置文件、栈层图标、pending 队列。世界数据搬迁（世界栈归档/导入/提升）由
 * {@code WorldStackTimeline} 负责；本体存档由 {@code ServerBackup} 负责；RCON 命令
 * 只审计命令本身，不承诺世界回滚。这样不会为一次配置保存复制整个 world。
 */
public final class WebAuditService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final long MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024;
    private static final WebAuditService INSTANCE = new WebAuditService();

    private final Path serverRoot;
    private final Path configRoot;
    private final Path root;

    private WebAuditService() {
        Path game;
        try {
            game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        } catch (Throwable t) {
            game = Path.of(".").toAbsolutePath().normalize();
        }
        this.serverRoot = game;
        Path config;
        try {
            config = StoragePaths.configRoot();
        } catch (Throwable t) {
            config = game.resolve("config").resolve("samsara");
        }
        this.configRoot = config;
        this.root = game.resolve("backups").resolve("samsara").resolve("web-audit");
    }

    public static WebAuditService get() {
        return INSTANCE;
    }

    /** 由 LocalWebServer 的 authorized 钩子调用；GET/预览/只读操作自动跳过。 */
    public static void capture(HttpExchange exchange) {
        try {
            if (exchange == null) return;
            String method = exchange.getRequestMethod();
            if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String actor = "admin";
            AgentAuthService.AuthContext context = AgentAuthService.getInstance().context(exchange);
            if (context != null) {
                actor = context.actor();
            }
            get().capture(path, actor);
        } catch (Throwable t) {
            // 审计失败绝不能阻塞配置保存。
            Samsara.LOGGER.debug("[web] Web 审计快照失败: {}", t.getMessage());
        }
    }

    private synchronized void capture(String path, String actor) {
        if (path == null || path.isBlank() || isReadOnly(path)) return;
        try {
            String id = "wa-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
            Path dir = root.resolve(id);
            Path filesDir = dir.resolve("files");
            Files.createDirectories(filesDir);
            List<FileInfo> files = new ArrayList<>();
            long[] total = {0};
            for (Path scope : scopes()) {
                capturePath(scope, filesDir, files, total);
            }
            Entry entry = new Entry();
            entry.id = id;
            entry.createdAt = System.currentTimeMillis();
            entry.actor = actor == null || actor.isBlank() ? "admin" : actor;
            entry.action = path;
            entry.target = path;
            entry.status = "captured";
            entry.rollbackable = rollbackable(path);
            entry.note = entry.rollbackable
                    ? "回滚会恢复快照中的 Web 配置文件；世界数据另见世界栈时间线/本体备份。"
                    : "该操作可能改世界数据或执行任意命令；配置文件快照可回滚，世界数据请用世界栈时间线/本体备份。";
            entry.fileCount = files.size();
            entry.bytes = total[0];
            entry.files = files;
            writeManifest(dir, entry);
            if (entry.fileCount == 0) {
                Files.deleteIfExists(dir.resolve("files"));
                Files.deleteIfExists(dir.resolve("manifest.json"));
                Files.deleteIfExists(dir);
            }
        } catch (Throwable t) {
            Samsara.LOGGER.debug("[web] Web 审计写入失败 {}: {}", path, t.getMessage());
        }
    }

    /**
     * 容器编辑审计：不复制配置文件，只保存编辑前的容器槽位快照。
     * 该记录不可自动回滚（容器属于世界数据），但可在 Web 审计里查看编辑前内容。
     */
    public synchronized void captureContainer(String action, String actor, String detail,
                                              String snapshotJson) {
        captureSnapshotFile("container.json", action, actor, detail,
                "容器编辑审计（不可自动回滚）：", snapshotJson);
    }

    /** 玩家背包/末影箱写入审计；与容器一样只保存编辑前快照，不自动回滚。 */
    public synchronized void captureInventory(String action, String actor, String detail,
                                              String snapshotJson) {
        captureSnapshotFile("inventory.json", action, actor, detail,
                "玩家背包编辑审计（不可自动回滚）：", snapshotJson);
    }

    private void captureSnapshotFile(String fileName, String action, String actor, String detail,
                                     String notePrefix, String snapshotJson) {
        try {
            String id = "wa-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
            Path dir = root.resolve(id);
            Files.createDirectories(dir);
            String body = snapshotJson == null ? "{}" : snapshotJson;
            Files.writeString(dir.resolve(fileName), body, StandardCharsets.UTF_8);
            Entry entry = new Entry();
            entry.id = id;
            entry.createdAt = System.currentTimeMillis();
            entry.actor = actor == null || actor.isBlank() ? "admin" : actor;
            entry.action = action == null || action.isBlank() ? "/api/inventory/write" : action;
            entry.target = entry.action;
            entry.status = "captured";
            entry.rollbackable = false;
            entry.note = notePrefix + (detail == null ? "" : detail);
            entry.fileCount = 0;
            entry.bytes = body.getBytes(StandardCharsets.UTF_8).length;
            entry.files = new ArrayList<>();
            writeManifest(dir, entry);
        } catch (Throwable t) {
            Samsara.LOGGER.debug("[web] 背包审计写入失败: {}", t.getMessage());
        }
    }

    public List<Map<String, Object>> list(int limit) {
        return listPage(0, limit <= 0 ? Integer.MAX_VALUE : limit);
    }

    /** 审计分页：按目录时间倒序跳过 offset 条，只解析当前页 manifest。 */
    public List<Map<String, Object>> listPage(int offset, int limit) {
        int skip = Math.max(0, offset);
        int max = limit <= 0 ? 30 : Math.min(200, limit);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())::iterator) {
                if (skip > 0) {
                    skip--;
                    continue;
                }
                Entry entry = readManifest(dir);
                if (entry == null) continue;
                out.add(entry.toMap());
                if (out.size() >= max) break;
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public Map<String, Object> preview(String id) {
        Entry entry = find(id);
        if (entry == null) return error("审计记录不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("entry", entry.toMap());
        out.put("restorable", entry.rollbackable);
        if (entry.files.isEmpty()) {
            Path container = root.resolve(entry.id).resolve("container.json");
            String snapshot = "";
            if (Files.isRegularFile(container)) {
                try {
                    snapshot = Files.readString(container, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
            out.put("changes", List.of());
            out.put("diffs", List.of());
            out.put("diff", snapshot);
            out.put("changedCount", 0);
            out.put("containerSnapshot", snapshot);
            return out;
        }
        try {
            DiffResult diff = diff(entry);
            out.put("changes", diff.changes);
            out.put("diffs", diff.diffs);
            out.put("diff", diff.text);
            out.put("changedCount", diff.changed);
        } catch (Exception e) {
            out.put("changes", List.of());
            out.put("diffs", List.of());
            out.put("diff", "");
            out.put("changedCount", 0);
            out.put("diffError", e.getMessage());
        }
        return out;
    }

    /** 对比快照与当前文件；文本文件给出逐行 +/- 差异。 */
    private DiffResult diff(Entry entry) throws IOException {
        Path filesDir = root.resolve(entry.id).resolve("files");
        Map<String, FileInfo> snapshot = new LinkedHashMap<>();
        for (FileInfo file : entry.files) snapshot.put(file.path, file);
        Map<String, CurrentFile> current = currentFiles();
        List<String> paths = new ArrayList<>(snapshot.keySet());
        for (String path : current.keySet()) {
            if (!snapshot.containsKey(path)) paths.add(path);
        }
        paths.sort(String::compareTo);
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int changed = 0;
        for (String path : paths) {
            FileInfo before = snapshot.get(path);
            CurrentFile after = current.get(path);
            String status = before == null ? "added"
                    : after == null ? "removed"
                    : before.sha256.equals(after.sha256) ? "unchanged" : "modified";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            item.put("status", status);
            item.put("beforeSize", before == null ? -1 : before.size);
            item.put("afterSize", after == null ? -1 : after.size);
            changes.add(item);
            if ("unchanged".equals(status)) continue;
            changed++;
            if (text.length() > 240_000) continue;
            String beforeText = before == null ? "" : readText(filesDir, path);
            String afterText = after == null ? "" : readText(after.path);
            String fileDiff;
            if (beforeText == null && afterText == null) {
                fileDiff = "（二进制文件，仅比较 SHA-256）\n";
            } else {
                fileDiff = lineDiff(beforeText == null ? "" : beforeText,
                        afterText == null ? "" : afterText);
            }
            Map<String, Object> diffItem = new LinkedHashMap<>();
            diffItem.put("path", path);
            diffItem.put("status", status);
            diffItem.put("diff", fileDiff);
            diffs.add(diffItem);
            text.append("--- ").append(path).append('\n');
            text.append("+++ ").append(path).append('\n');
            text.append(fileDiff);
            text.append('\n');
        }
        return new DiffResult(changes, diffs, text.toString(), changed);
    }

    private Map<String, CurrentFile> currentFiles() throws IOException {
        Map<String, CurrentFile> out = new LinkedHashMap<>();
        for (Path scope : scopes()) {
            if (scope == null || !Files.exists(scope)) continue;
            if (Files.isRegularFile(scope)) {
                addCurrent(scope, out);
            } else if (Files.isDirectory(scope)) {
                try (Stream<Path> stream = Files.walk(scope)) {
                    for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile).sorted()::iterator) {
                        if (file.toAbsolutePath().normalize().startsWith(root)) continue;
                        addCurrent(file, out);
                    }
                }
            }
        }
        return out;
    }

    private void addCurrent(Path file, Map<String, CurrentFile> out) throws IOException {
        String relative = serverRoot.relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        if (relative.startsWith("../") || relative.contains("/../")) return;
        out.put(relative, new CurrentFile(file, Files.size(file), sha256(file)));
    }

    private static String readText(Path filesDir, String relative) {
        Path path = filesDir.resolve(relative).normalize();
        if (!path.startsWith(filesDir.normalize()) || !Files.isRegularFile(path)) return null;
        return readText(path);
    }

    private static String readText(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json")
                || name.endsWith(".properties") || name.endsWith(".txt")
                || name.endsWith(".mcmeta") || name.endsWith(".toml")
                || name.endsWith(".conf") || name.endsWith(".ini"))) {
            return null;
        }
        try {
            if (Files.size(path) > 256 * 1024) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 简单逐行 diff（LCS）；配置文本通常很小，够用且不引入额外依赖。 */
    private static String lineDiff(String before, String after) {
        String[] a = before.split("\\R", -1);
        String[] b = after.split("\\R", -1);
        if (a.length > 4000) a = java.util.Arrays.copyOf(a, 4000);
        if (b.length > 4000) b = java.util.Arrays.copyOf(b, 4000);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int j = 0;
        int lines = 0;
        while (i < a.length && j < b.length && lines < 800) {
            if (a[i].equals(b[j])) {
                out.append(' ').append(a[i++]).append('\n');
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.append('-').append(a[i++]).append('\n');
            } else {
                out.append('+').append(b[j++]).append('\n');
            }
            lines++;
        }
        while (i < a.length && lines++ < 800) out.append('-').append(a[i++]).append('\n');
        while (j < b.length && lines++ < 800) out.append('+').append(b[j++]).append('\n');
        return out.toString();
    }

    private record CurrentFile(Path path, long size, String sha256) {
    }

    private record DiffResult(List<Map<String, Object>> changes,
                              List<Map<String, Object>> diffs,
                              String text, int changed) {
    }

    public Map<String, Object> rollback(String id, boolean confirm) {
        if (!confirm) return error("回滚需要确认");
        Entry entry = find(id);
        if (entry == null) return error("审计记录不存在");
        if (!entry.rollbackable) {
            return error("该记录不可自动回滚：请使用世界栈时间线或本体备份恢复世界数据");
        }
        Path dir = root.resolve(entry.id);
        Path filesDir = dir.resolve("files");
        List<FileInfo> missing = new ArrayList<>();
        for (FileInfo file : entry.files) {
            Path source = resolveSnapshot(filesDir, file.path);
            if (source == null || !Files.isRegularFile(source)) missing.add(file);
        }
        if (!missing.isEmpty()) {
            return error("快照缺少 " + missing.size() + " 个文件，未执行回滚");
        }
        String beforeId = null;
        try {
            beforeId = captureBeforeRollback();
            int restored = 0;
            for (FileInfo file : entry.files) {
                Path source = resolveSnapshot(filesDir, file.path);
                Path target = resolveServer(file.path);
                if (source == null || target == null) throw new IOException("非法审计路径");
                Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling(target.getFileName() + ".audit-restore");
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                restored++;
            }
            reloadConfigBestEffort();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "已回滚 " + restored + " 个文件" + (beforeId == null ? ""
                    : "；回滚前状态保存在 " + beforeId));
            out.put("restoredFiles", restored);
            out.put("beforeAuditId", beforeId);
            return out;
        } catch (Exception e) {
            return error("回滚失败：" + e.getMessage());
        }
    }

    private String captureBeforeRollback() {
        String id = "wa-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
        try {
            Path dir = root.resolve(id);
            Path filesDir = dir.resolve("files");
            Files.createDirectories(filesDir);
            List<FileInfo> files = new ArrayList<>();
            long[] total = {0};
            for (Path scope : scopes()) capturePath(scope, filesDir, files, total);
            Entry entry = new Entry();
            entry.id = id;
            entry.createdAt = System.currentTimeMillis();
            entry.actor = "system-rollback";
            entry.action = "/api/audit/rollback";
            entry.target = "回滚前当前状态";
            entry.status = "captured";
            entry.rollbackable = true;
            entry.note = "回滚动作执行前的当前 Web 配置快照。";
            entry.fileCount = files.size();
            entry.bytes = total[0];
            entry.files = files;
            writeManifest(dir, entry);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private Entry find(String id) {
        if (id == null || !id.matches("wa-[0-9]+-[0-9]+")) return null;
        return readManifest(root.resolve(id));
    }

    private Entry readManifest(Path dir) {
        Path file = dir.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
            if (entry == null || entry.id == null || entry.id.isBlank()) return null;
            if (entry.files == null) entry.files = new ArrayList<>();
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeManifest(Path dir, Entry entry) throws IOException {
        Files.createDirectories(dir);
        Path temp = dir.resolve("manifest.json.tmp");
        Files.writeString(temp, GSON.toJson(entry), StandardCharsets.UTF_8);
        Files.move(temp, dir.resolve("manifest.json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private List<Path> scopes() {
        List<Path> out = new ArrayList<>();
        out.add(configRoot);
        for (String name : List.of("server.properties", "eula.txt", "whitelist.json",
                "ops.json", "banned-players.json", "banned-ips.json")) {
            out.add(serverRoot.resolve(name));
        }
        out.add(serverRoot.resolve("world").resolve("samsara").resolve("stack-icons"));
        Path samsara = serverRoot.resolve("world").resolve("samsara");
        if (Files.isDirectory(samsara)) {
            try (Stream<Path> stream = Files.list(samsara)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("pending-"))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private void capturePath(Path path, Path filesDir, List<FileInfo> files, long[] total)
            throws IOException {
        if (path == null || !Files.exists(path)) return;
        if (Files.isRegularFile(path)) {
            captureFile(path, filesDir, files, total);
            return;
        }
        if (!Files.isDirectory(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .sorted()::iterator) {
                if (file.toAbsolutePath().normalize().startsWith(root)) continue;
                captureFile(file, filesDir, files, total);
            }
        }
    }

    private void captureFile(Path file, Path filesDir, List<FileInfo> files, long[] total)
            throws IOException {
        long size = Files.size(file);
        if (total[0] + size > MAX_SNAPSHOT_BYTES) return;
        String relative = serverRoot.relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        if (relative.startsWith("../") || relative.contains("/../")) return;
        Path target = filesDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        FileInfo info = new FileInfo();
        info.path = relative;
        info.size = size;
        info.sha256 = sha256(file);
        files.add(info);
        total[0] += size;
    }

    private Path resolveSnapshot(Path filesDir, String relative) {
        if (relative == null) return null;
        Path path = filesDir.resolve(relative).normalize();
        return path.startsWith(filesDir.normalize()) ? path : null;
    }

    private Path resolveServer(String relative) {
        if (relative == null) return null;
        Path path = serverRoot.resolve(relative).normalize();
        return path.startsWith(serverRoot) ? path : null;
    }

    private boolean isReadOnly(String path) {
        return path.contains("/preview") || path.contains("/timeline/export")
                || path.contains("/timeline/snapshots") || path.endsWith("/jobs")
                || path.contains("/audit/") || path.equals("/api/audit")
                || path.equals("/api/reload") || path.endsWith("/reload")
                || path.endsWith("/identity/audit") || path.endsWith("/identity/resolve")
                || path.endsWith("/map") || path.endsWith("/map/players")
                || path.endsWith("/map/live") || path.endsWith("/map/tile")
                // 容器写入使用专用审计快照（container.json），不再复制整套配置文件
                || path.equals("/api/containers/write");
    }

    private boolean rollbackable(String path) {
        if (path.contains("/world-stack") && !path.contains("/world-stack/icon")) return false;
        if (path.contains("/server/backups")) return false;
        if (path.contains("/server/console")) return false;
        if (path.contains("/diff/chunk/restore") || path.contains("/diff/view")) return false;
        return true;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private void reloadConfigBestEffort() {
        try {
            ConfigManager.getInstance().reloadAll();
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    public static final class Entry {
        public String id = "";
        public long createdAt;
        public String actor = "admin";
        public String action = "";
        public String target = "";
        public String status = "captured";
        public boolean rollbackable = true;
        public String note = "";
        public int fileCount;
        public long bytes;
        public List<FileInfo> files = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("createdAt", createdAt);
            out.put("actor", actor);
            out.put("action", action);
            out.put("target", target);
            out.put("status", status);
            out.put("rollbackable", rollbackable);
            out.put("note", note);
            out.put("fileCount", fileCount);
            out.put("bytes", bytes);
            return out;
        }
    }

    public static final class FileInfo {
        public String path = "";
        public long size;
        public String sha256 = "";
    }
}
