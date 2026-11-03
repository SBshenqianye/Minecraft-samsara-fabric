package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.storage.StoragePaths;
import com.shenqianye.samsara.track.view.ViewTime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 单区块回溯（F-165，保存方块状态）。
 * <p>
 * 只读取目标区块的 {@code diff/live} 记录，筛出 {@code timeMs > target} 的最早一条
 * pre-state 并写回世界；写前把整个 region 的 {@code .mca}（region/entities/poi）
 * 复制到 {@code config/samsara/backups/chunk-restore_<时间>/}，失败时先用内存中的
 * 原始方块状态回滚，磁盘备份留作停服后的最后兜底。
 * <p>
 * 与只读回看 {@link DiffViewService} 严格分离：本类会写世界，回看绝不调用本类。
 */
public final class ChunkRestoreService {
    public static final String BACKUP_PREFIX = "chunk-restore_";
    private static final int KEEP_BACKUPS = 5;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final ChunkRestoreService INSTANCE = new ChunkRestoreService();

    private ChunkRestoreService() {
    }

    public static ChunkRestoreService getInstance() {
        return INSTANCE;
    }

    /** 预览/摘要结果；{@code changedBlocks=-1} 表示区块未加载、无法比对当前状态。 */
    public record Summary(boolean ok, String error, ChunkDiffTarget target, int cx, int cz,
                          int editCount, int candidateBlocks, int changedBlocks,
                          long earliestMs, long latestMs, long targetMs,
                          boolean protectedArea, boolean rewindRunning, boolean loaded,
                          boolean onDisk) {
        public boolean canApply() {
            return ok && !protectedArea && !rewindRunning && editCount > 0;
        }
    }

    public Summary inspect(MinecraftServer server, String stackId, String dimension,
                           int cx, int cz, String toSpec, boolean loadChunk) {
        ChunkDiffTarget target = ChunkDiffTarget.resolve(server, stackId, dimension);
        if (target == null) {
            return new Summary(false, "找不到目标栈层/维度（stack=" + stackId
                    + " dim=" + dimension + "）", null, cx, cz, 0, 0, -1,
                    0, 0, 0, false, false, false, false);
        }
        List<DiffStore.Edit> edits = target.readEdits(cx, cz, false);
        long targetMs;
        try {
            targetMs = toSpec == null || toSpec.isBlank()
                    ? ChunkDiffLogic.defaultTarget(edits) : ViewTime.parseAbsolute(toSpec);
        } catch (IllegalArgumentException e) {
            return new Summary(false, e.getMessage(), target, cx, cz, edits.size(), 0, -1,
                    0, 0, 0, false, false, false, false);
        }
        ChunkDiffLogic.Stats stats = ChunkDiffLogic.stats(edits, targetMs);
        boolean protectedArea = AreaService.getInstance()
                .isProtected(target.level().dimension(), cx, cz);
        boolean rewindRunning = RewindEngine.getInstance().running();
        boolean loaded = target.loaded(cx, cz);
        boolean onDisk = target.existsOnDisk(cx, cz);
        if (!edits.isEmpty() && !loaded && !onDisk) {
            return new Summary(false, "区块 (" + cx + "," + cz
                    + ") 不在磁盘且未加载；为避免生成新区块，已拒绝操作",
                    target, cx, cz, edits.size(), stats.blockCount(), -1,
                    stats.earliestMs(), stats.latestMs(), targetMs,
                    protectedArea, rewindRunning, false, false);
        }
        if (loadChunk && !loaded) {
            target.level().getChunk(cx, cz);
            loaded = true;
        }
        int changed = -1;
        if (loaded && !edits.isEmpty()) {
            changed = countChanges(target.level(), ChunkDiffLogic.plan(edits, targetMs));
        }
        return new Summary(true, null, target, cx, cz, edits.size(), stats.blockCount(), changed,
                stats.earliestMs(), stats.latestMs(), targetMs,
                protectedArea, rewindRunning, loaded, onDisk);
    }

    private static int countChanges(ServerLevel level, List<ChunkDiffLogic.BlockChange> plan) {
        int changed = 0;
        for (ChunkDiffLogic.BlockChange change : plan) {
            BlockState state = stateOf(change.stateId());
            if (state == null) {
                continue;
            }
            if (!level.getBlockState(new BlockPos(change.x(), change.y(), change.z())).equals(state)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 执行回溯。调用方必须已完成二次确认（命令 {@code --confirm} / Web {@code confirm=true}）。
     *
     * @return 结果映射；{@code ok=false} 时不会写世界
     */
    public Map<String, Object> restore(MinecraftServer server, String stackId, String dimension,
                                       int cx, int cz, String toSpec) {
        Summary summary = inspect(server, stackId, dimension, cx, cz, toSpec, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!summary.ok()) {
            out.put("error", summary.error());
            return out;
        }
        out.put("layer", summary.target().layerId());
        out.put("dimension", summary.target().dimShort());
        out.put("cx", cx);
        out.put("cz", cz);
        out.put("targetMs", summary.targetMs());
        out.put("editCount", summary.editCount());
        out.put("changedBlocks", summary.changedBlocks());
        if (summary.protectedArea()) {
            out.put("error", "区块受圈地保护，拒绝回溯");
            return out;
        }
        if (summary.rewindRunning()) {
            out.put("error", "时间倒流进行中，拒绝单区块回溯");
            return out;
        }
        List<DiffStore.Edit> edits = summary.target().readEdits(cx, cz, false);
        List<ChunkDiffLogic.BlockChange> plan = ChunkDiffLogic.plan(edits, summary.targetMs());
        if (plan.isEmpty()) {
            out.put("ok", true);
            out.put("applied", 0);
            out.put("message", "目标时间之前没有可回溯的记录（无需改动）");
            return out;
        }
        Path backup;
        try {
            backup = backupRegion(summary.target(), cx, cz);
        } catch (IOException e) {
            out.put("error", "写前备份失败，已取消回溯: " + e.getMessage());
            return out;
        }
        out.put("backup", backup == null ? "" : backup.toString());
        ServerLevel level = summary.target().level();
        level.getChunk(cx, cz);
        int applied;
        try {
            applied = apply(level, plan);
        } catch (RuntimeException e) {
            Samsara.LOGGER.error("[单区块回溯] 应用失败，已尝试内存回滚: {}", e.getMessage());
            out.put("error", "应用失败，已回滚: " + e.getMessage());
            return out;
        }
        out.put("ok", true);
        out.put("applied", applied);
        out.put("message", "区块 (" + cx + "," + cz + ") 已回溯到 "
                + ViewTime.format(summary.targetMs()) + " 之前 · 改动 " + applied + " 格"
                + (backup == null ? "（备份文件不存在，仅内存回滚）" : " · 备份 " + backup));
        Samsara.LOGGER.info("[单区块回溯] {} / {} 区块 ({},{}) → {} · 改动 {} 格 · 备份 {}",
                summary.target().layerId(), summary.target().dimShort(), cx, cz,
                ViewTime.format(summary.targetMs()), applied, backup);
        return out;
    }

    private static int apply(ServerLevel level, List<ChunkDiffLogic.BlockChange> plan) {
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        boolean wasRecording = DiffRecorder.recording();
        int applied = 0;
        DiffRecorder.setEnabled(false);
        try {
            for (ChunkDiffLogic.BlockChange change : plan) {
                BlockState target = stateOf(change.stateId());
                if (target == null) {
                    continue;
                }
                BlockPos pos = new BlockPos(change.x(), change.y(), change.z());
                BlockState current = level.getBlockState(pos);
                if (current.equals(target)) {
                    continue;
                }
                originals.put(pos, current);
                if (level.setBlock(pos, target, 2)) {
                    applied++;
                }
            }
        } catch (RuntimeException e) {
            for (Map.Entry<BlockPos, BlockState> entry : originals.entrySet()) {
                try {
                    level.setBlock(entry.getKey(), entry.getValue(), 2);
                } catch (RuntimeException ignored) {
                    // 尽力回滚；磁盘备份仍可用
                }
            }
            throw e;
        } finally {
            DiffRecorder.setEnabled(wasRecording);
        }
        return applied;
    }

    private static BlockState stateOf(int stateId) {
        try {
            return Block.stateById(stateId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 写前备份该区块所在 region 的 region/entities/poi 文件；返回备份目录（都不存在时 null）。 */
    private static Path backupRegion(ChunkDiffTarget target, int cx, int cz) throws IOException {
        McaRegion.RegionPos rp = McaRegion.RegionPos.ofChunk(cx, cz);
        String name = "r." + rp.rx() + "." + rp.rz() + ".mca";
        String ts = LocalDateTime.now(ZoneId.systemDefault()).format(TS);
        Path base = StoragePaths.get().configRootPath().resolve("backups")
                .resolve(BACKUP_PREFIX + ts)
                .resolve(target.layerId()).resolve(target.dimShort());
        boolean copied = false;
        for (String folder : new String[]{"region", "entities", "poi"}) {
            Path src = target.dimRoot().resolve(folder).resolve(name);
            if (!Files.isRegularFile(src)) {
                continue;
            }
            Path dst = base.resolve(folder).resolve(name);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            copied = true;
        }
        if (!copied) {
            return null;
        }
        pruneBackups(base.getParent().getParent().getParent());
        return base;
    }

    private static void pruneBackups(Path backupsRoot) {
        if (backupsRoot == null || !Files.isDirectory(backupsRoot)) {
            return;
        }
        try (Stream<Path> list = Files.list(backupsRoot)) {
            List<Path> dirs = list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(BACKUP_PREFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (int i = 0; i < dirs.size() - KEEP_BACKUPS; i++) {
                DiffStore.deleteRecursively(dirs.get(i));
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[单区块回溯] 备份清理失败: {}", e.getMessage());
        }
    }

    /** 命令层/Web 共用的摘要映射。 */
    public Map<String, Object> summaryMap(MinecraftServer server, String stackId, String dimension,
                                          int cx, int cz, String toSpec, boolean loadChunk) {
        Summary s = inspect(server, stackId, dimension, cx, cz, toSpec, loadChunk);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", s.ok());
        if (!s.ok()) {
            out.put("error", s.error());
        }
        if (s.target() != null) {
            out.put("layer", s.target().layerId());
            out.put("dimension", s.target().dimShort());
        }
        out.put("cx", cx);
        out.put("cz", cz);
        out.put("editCount", s.editCount());
        out.put("candidateBlocks", s.candidateBlocks());
        out.put("changedBlocks", s.changedBlocks());
        out.put("earliest", s.earliestMs());
        out.put("latest", s.latestMs());
        out.put("target", s.targetMs());
        out.put("protected", s.protectedArea());
        out.put("rewindRunning", s.rewindRunning());
        out.put("loaded", s.loaded());
        out.put("onDisk", s.onDisk());
        out.put("canApply", s.canApply());
        return out;
    }
}
