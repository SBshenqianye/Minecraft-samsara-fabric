package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackManifest;
import com.shenqianye.samsara.season.StackPaths;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 单区块 diff 的栈层/维度解析（F-165 / F-166 共用）。
 * <p>
 * 只做只读路径解析与记录读取：不打开 {@link DiffStore}（其构造会创建目录），
 * 因此预览、回看不会在磁盘上留下任何新目录/文件。
 */
public record ChunkDiffTarget(String layerId, String dimShort, String dimKey,
                              ServerLevel level, Path dataRoot, Path dimRoot) {

    /** 三界短名（地图/命令使用）。 */
    public static final List<String> DIMENSIONS = List.of("overworld", "the_nether", "the_end");

    public static ChunkDiffTarget resolve(MinecraftServer server, String stackId, String dimension) {
        if (server == null) {
            return null;
        }
        Path worldDir = StackPaths.worldDir(server);
        StackManifest manifest = StackManifest.load(worldDir);
        StackLayer layer = layer(manifest, stackId);
        if (layer == null) {
            return null;
        }
        String dim = shortDimension(layer, dimension);
        if (dim == null) {
            return null;
        }
        ResourceKey<Level> key = StackPaths.key(layer, dim);
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return null;
        }
        Path dataRoot = StackPaths.dataRootFor(worldDir, layer.id);
        Path dimRoot = StackPaths.layerDimensionFor(worldDir, layer.id, dim);
        String dimKey = key.identifier().getNamespace() + "/" + key.identifier().getPath();
        return new ChunkDiffTarget(layer.id, dim, dimKey, level, dataRoot, dimRoot);
    }

    /** 根据在线维度反查它所属的栈层（Web 回看/命令默认维度）。 */
    public static ChunkDiffTarget ofLevel(ServerLevel level) {
        if (level == null) {
            return null;
        }
        return resolve(level.getServer(), StackPaths.layerIdOf(level.dimension()),
                StackPaths.dimensionOf(level.dimension()));
    }

    /** 当前维度短名（overworld / the_nether / the_end）；无法识别返回 null。 */
    public static String shortDimension(StackLayer layer, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        String value = dimension.trim().toLowerCase(Locale.ROOT);
        for (String dim : DIMENSIONS) {
            if (dim.equals(value)) {
                return dim;
            }
            if (StackPaths.key(layer, dim).identifier().toString().equalsIgnoreCase(value)) {
                return dim;
            }
        }
        return null;
    }

    private static StackLayer layer(StackManifest manifest, String stackId) {
        if (manifest == null) {
            return null;
        }
        if (stackId == null || stackId.isBlank() || StackPaths.TOP_ID.equals(stackId)) {
            return manifest.top();
        }
        return manifest.byId(stackId);
    }

    /**
     * 读取目标区块的 diff 记录。
     *
     * @param includeCycles true = 连同 {@code diff/cycle&lt;id&gt;} 冻结恢复源一起读（只读回看）；
     *                      false = 只读常开窗口 {@code diff/live}（保存型回溯）。
     */
    public List<DiffStore.Edit> readEdits(int cx, int cz, boolean includeCycles) {
        Set<String> seen = new LinkedHashSet<>();
        List<DiffStore.Edit> out = new ArrayList<>();
        Path live = dataRoot.resolve("diff").resolve(DiffStore.LIVE).resolve(dimKey);
        collect(DiffStore.readChunkFile(live, cx, cz), out, seen);
        if (includeCycles) {
            Path diffRoot = dataRoot.resolve("diff");
            if (Files.isDirectory(diffRoot)) {
                List<Path> cycles = new ArrayList<>();
                try (Stream<Path> list = Files.list(diffRoot)) {
                    list.filter(Files::isDirectory)
                            .filter(p -> p.getFileName().toString().startsWith(DiffStore.CYCLE_PREFIX))
                            .forEach(cycles::add);
                } catch (IOException | java.io.UncheckedIOException ignored) {
                    // 目录不可读：只使用 live 已读到的记录
                }
                cycles.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path cycle : cycles) {
                    collect(DiffStore.readChunkFile(cycle.resolve(dimKey), cx, cz), out, seen);
                }
            }
        }
        out.sort(Comparator.comparingLong((DiffStore.Edit e) -> e.timeMs)
                .thenComparingInt(e -> e.y)
                .thenComparingInt(e -> e.x)
                .thenComparingInt(e -> e.z));
        return out;
    }

    private static void collect(List<DiffStore.Edit> source, List<DiffStore.Edit> out,
                                Set<String> seen) {
        for (DiffStore.Edit e : source) {
            String key = e.x + "," + e.y + "," + e.z + "," + e.timeMs + "," + e.stateId;
            if (seen.add(key)) {
                out.add(e);
            }
        }
    }

    /** 该区块是否已加载（不触发加载）。 */
    public boolean loaded(int cx, int cz) {
        return level.isLoaded(new net.minecraft.core.BlockPos(cx << 4, 0, cz << 4));
    }

    /** 该区块是否存在于磁盘 region（防止回看触发生成新区块）。 */
    public boolean existsOnDisk(int cx, int cz) {
        return McaRegion.chunkExists(dimRoot, cx, cz);
    }
}
