package com.shenqianye.samsara.season;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.shenqianye.samsara.season.WorldStackAdmin.*;

/** 导入栈层前的只读预览与来源检查。 */
public final class WorldStackImportPreview {
    private WorldStackImportPreview() {
    }

    public static Preview preview(Path worldDir, Path serverRoot, List<Path> importRoots,
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
        if (!WorldStackImportGuard.allowed(real, worldDir, serverRoot, importRoots)) {
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
        info.sessionLocked = WorldStackImportGuard.sessionLocked(real.resolve("session.lock"));
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
        p.playerConflicts = WorldStackImportGuard.playerConflicts(worldDir, real);
        p.conflictFiles = WorldStackImportGuard.conflictFiles(worldDir, real);
        return info;
    }

    // ---- 排序/归档 ----

}



