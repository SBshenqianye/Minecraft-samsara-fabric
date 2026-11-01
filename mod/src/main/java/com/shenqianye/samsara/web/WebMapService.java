package com.shenqianye.samsara.web;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.claim.ClaimRangeConfig;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.geometry.Geometry;
import com.shenqianye.samsara.season.SpawnHistory;
import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackManifest;
import com.shenqianye.samsara.season.StackPaths;
import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 地图标签的数据服务：按 v0.24 世界栈读取各栈层的 region 文件与圈地白名单。
 *
 * <p>地形底图由 {@link MapTerrainRenderer} 按需渲染成 PNG；这里只负责：
 * 读取栈清单/栈层元数据、列出各层维度 region、汇总圈地保护区块与旗帜、
 * 维护内存热缓存 + {@code world/samsara/web-map-cache/} 磁盘缓存。
 *
 * <p>只读世界数据，不加载区块，也不写 {@code level.dat}/维度目录；
 * 地图缓存目录可随时删除。
 */
public class WebMapService {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Set<Integer> SCALES = Set.of(1, 2, 4, 8);
    private static final List<String> DIMENSIONS =
            List.of("overworld", "the_nether", "the_end");
    private static final long MANIFEST_RESCAN_MS = 2000L;

    private final Path worldRoot;
    private final WebMapTileCache cache;
    private final Map<Path, ClaimsSnapshot> claimsCache = new ConcurrentHashMap<>();
    private volatile StackManifest manifestCache;
    private volatile long manifestCheckedAt;
    private volatile long manifestStamp = Long.MIN_VALUE;

    public WebMapService() {
        this(StoragePaths.get().worldRoot());
    }

    WebMapService(Path worldRoot) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        this.cache = new WebMapTileCache(this.worldRoot.resolve("samsara").resolve("web-map-cache"));
    }

    /** 返回地图页需要的栈层、维度、region 列表、保护区块、旗帜与出生点历史。 */
    public Map<String, Object> model() {
        Map<String, Object> out = new LinkedHashMap<>();
        Path world = worldRoot;
        out.put("ok", true);
        out.put("worldRoot", world.toString());
        out.put("generatedAt", System.currentTimeMillis());
        out.put("claimRange", claimRangeModel());
        out.put("cache", cache.stats());

        List<StackLayer> layers = layers(world);
        List<Map<String, Object>> stacks = new ArrayList<>(layers.size());
        for (StackLayer layer : layers) {
            stacks.add(stackModel(world, layer));
        }
        out.put("stacks", stacks);
        out.put("topStackId", layers.isEmpty() ? StackPaths.TOP_ID : layers.get(0).id);
        return out;
    }

    /** 返回一个 region 的地形 PNG；栈层/维度/坐标/缩放非法或文件不存在时返回 null。 */
    public byte[] tile(String stackId, String dimension, int regionX, int regionZ, int scale) {
        return tile(stackId, dimension, regionX, regionZ, scale, true);
    }

    public byte[] tile(String stackId, String dimension, int regionX, int regionZ, int scale,
                       boolean shading) {
        if (!SCALES.contains(scale)) {
            return null;
        }
        Path world = worldRoot;
        StackLayer layer = resolveLayer(world, stackId);
        if (layer == null) {
            return null;
        }
        String shortDim = shortDimension(layer, dimension);
        if (shortDim == null) {
            return null;
        }
        Path dir = regionDir(world, layer, shortDim);
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            String dimKey = StackPaths.key(layer, shortDim).identifier().toString();
            byte[] cached = cache.get(layer.id, dimKey, regionX, regionZ, scale, shading,
                    modified, size);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = MapTerrainRenderer.render(dir, regionX, regionZ, scale, shading);
            if (bytes == null) {
                return null;
            }
            cache.put(layer.id, dimKey, regionX, regionZ, scale, shading, modified, size, bytes);
            return bytes;
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.warn("[web] 地图 tile 渲染失败 {}/{}/{},{}: {}",
                    layer.id, shortDim, regionX, regionZ, e.getMessage());
            return null;
        }
    }

    // ---- 栈层模型 ----

    private Map<String, Object> stackModel(Path world, StackLayer layer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", layer.id);
        out.put("index", layer.index);
        out.put("top", layer.top);
        out.put("label", layer.top ? "world（栈顶）"
                : layer.id + "（第 " + (layer.index + 1) + " 层）");
        out.put("createdAt", layer.createdAt);
        out.put("seed", layer.seed);
        out.put("levelType", layer.levelType);
        out.put("spawn", Map.of("x", layer.spawnX, "y", layer.spawnY, "z", layer.spawnZ));
        out.put("metaPath", relative(world, layer.metaDir(world)));

        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String dim : DIMENSIONS) {
            dimensions.add(dimensionModel(world, layer, dim));
        }
        out.put("dimensions", dimensions);

        Path historyFile = StackPaths.spawnHistory(world, layer);
        SpawnHistory history = SpawnHistory.loadFrom(layer.metaDir(world));
        out.put("spawnHistory", spawnHistoryModel(world, layer, historyFile, history));
        return out;
    }

    private Map<String, Object> dimensionModel(Path world, StackLayer layer, String shortDim) {
        String dimKey = StackPaths.key(layer, shortDim).identifier().toString();
        Path dir = regionDir(world, layer, shortDim);
        List<int[]> regions = listRegions(dir);

        DimClaims claims = claimsFor(layer).get(dimKey);
        List<int[]> protectedChunks = new ArrayList<>();
        List<Map<String, Object>> banners = new ArrayList<>();
        if (claims != null) {
            for (ChunkCoord chunk : claims.protectedChunks()) {
                protectedChunks.add(new int[]{chunk.x, chunk.z});
            }
            protectedChunks.sort(Comparator.comparingInt((int[] value) -> value[0])
                    .thenComparingInt(value -> value[1]));
            for (ClaimNode node : claims.nodes()) {
                for (ClaimNode.BannerEntry banner : node.getBanners()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("x", banner.blockX());
                    item.put("y", banner.blockY());
                    item.put("z", banner.blockZ());
                    item.put("facing", banner.facing());
                    item.put("owner", node.getOwnerName() == null ? "" : node.getOwnerName());
                    item.put("ownerUUID", node.getOwnerUUID() == null ? ""
                            : node.getOwnerUUID().toString());
                    item.put("chunkX", node.getLocation().x);
                    item.put("chunkZ", node.getLocation().z);
                    List<Map<String, Object>> patterns = new ArrayList<>();
                    for (ClaimNode.PatternEntry pattern : banner.patterns()) {
                        patterns.add(Map.of("id", pattern.patternId(), "color", pattern.color()));
                    }
                    item.put("patterns", patterns);
                    banners.add(item);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", dimKey);
        out.put("dim", shortDim);
        out.put("name", dimensionName(shortDim));
        out.put("regions", regions);
        out.put("protectedChunks", protectedChunks);
        out.put("banners", banners);
        out.put("claimNodes", claims == null ? 0 : claims.nodes().size());
        out.put("protectedCount", protectedChunks.size());
        out.put("bannerCount", banners.size());
        out.put("bounds", bounds(protectedChunks, banners));
        return out;
    }

    private Map<String, Object> spawnHistoryModel(Path world, StackLayer layer, Path file,
                                                  SpawnHistory history) {
        List<Map<String, Object>> cycles = new ArrayList<>();
        int currentIndex = history.currentIndex();
        Path overworldDir = regionDir(world, layer, "overworld");
        Path netherDir = regionDir(world, layer, "the_nether");
        for (SpawnHistory.Cycle cycle : history.cycles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", cycle.index);
            item.put("x", cycle.x);
            item.put("z", cycle.z);
            item.put("createdAt", cycle.createdAt);
            item.put("source", cycle.source == null ? "" : cycle.source);
            item.put("current", cycle.index == currentIndex);
            item.put("overworld", spawnTarget(layer, "overworld", overworldDir,
                    cycle.x, cycle.z, layer.spawnY));
            // v0.24 的 spawn-history 只有主世界 x/z。下界基地按主世界坐标 /8 推算，
            // 明确标记 estimated=true，避免把推算值当成持久化事实。
            item.put("nether", spawnTarget(layer, "the_nether", netherDir,
                    Math.floorDiv(cycle.x, 8), Math.floorDiv(cycle.z, 8), 64));
            cycles.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", relative(world, file));
        out.put("exists", Files.isRegularFile(file));
        out.put("currentIndex", currentIndex);
        out.put("cycles", cycles);
        return out;
    }

    private Map<String, Object> spawnTarget(StackLayer layer, String shortDim, Path regionDir,
                                            int x, int z, int fallbackY) {
        Integer y = null;
        if (regionDir != null) {
            try {
                y = MapTerrainRenderer.surfaceY(regionDir, x, z);
                // 下界高度图会取到基岩顶，不能当落脚点。
                if ("the_nether".equals(shortDim) && y != null && y > 120) {
                    y = null;
                }
            } catch (IOException | RuntimeException e) {
                y = null;
            }
        }
        boolean estimated = y == null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", StackPaths.key(layer, shortDim).identifier().toString());
        out.put("x", x);
        out.put("y", estimated ? fallbackY : y);
        out.put("z", z);
        out.put("yEstimated", estimated);
        return out;
    }

    private static Map<String, Object> bounds(List<int[]> chunks,
                                              List<Map<String, Object>> banners) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] chunk : chunks) {
            minX = Math.min(minX, chunk[0] * 16);
            minZ = Math.min(minZ, chunk[1] * 16);
            maxX = Math.max(maxX, (chunk[0] + 1) * 16);
            maxZ = Math.max(maxZ, (chunk[1] + 1) * 16);
        }
        for (Map<String, Object> banner : banners) {
            int x = ((Number) banner.get("x")).intValue();
            int z = ((Number) banner.get("z")).intValue();
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1);
            maxZ = Math.max(maxZ, z + 1);
        }
        if (minX == Integer.MAX_VALUE) {
            return Map.of("minX", 0, "minZ", 0, "maxX", 0, "maxZ", 0);
        }
        return Map.of("minX", minX, "minZ", minZ, "maxX", maxX, "maxZ", maxZ);
    }

    // ---- 栈层/claims ----

    private List<StackLayer> layers(Path world) {
        StackManifest manifest = manifest(world);
        List<StackLayer> out = new ArrayList<>();
        StackLayer top = manifest.top();
        for (StackLayer layer : manifest.layers) {
            if (layer != null) {
                out.add(layer);
            }
        }
        if (top == null) {
            // 兼容首次启动、stack.json 还没写出的世界：临时把 world 当栈顶，不写盘。
            top = new StackLayer();
            top.id = StackPaths.TOP_ID;
            top.top = true;
            top.index = out.isEmpty() ? 0
                    : out.stream().mapToInt(layer -> layer.index).max().orElse(-1) + 1;
            SpawnHistory history = SpawnHistory.load(world);
            SpawnHistory.Cycle last = history.last();
            if (last != null) {
                top.spawnX = last.x;
                top.spawnZ = last.z;
            }
            out.add(top);
        }
        out.sort(Comparator.comparingInt((StackLayer layer) -> layer.index).reversed());
        return out;
    }

    private StackLayer resolveLayer(Path world, String stackId) {
        String wanted = stackId == null || stackId.isBlank() ? StackPaths.TOP_ID : stackId;
        List<StackLayer> layers = layers(world);
        if (layers.isEmpty()) {
            return null;
        }
        if (StackPaths.TOP_ID.equals(wanted)) {
            return layers.stream().filter(layer -> layer.top).findFirst().orElse(layers.get(0));
        }
        return layers.stream().filter(layer -> wanted.equals(layer.id)).findFirst().orElse(null);
    }

    private StackManifest manifest(Path world) {
        long now = System.currentTimeMillis();
        if (manifestCache != null && now - manifestCheckedAt < MANIFEST_RESCAN_MS) {
            return manifestCache;
        }
        long stamp = stamp(StackPaths.manifest(world));
        if (manifestCache == null || stamp != manifestStamp) {
            manifestCache = StackManifest.load(world);
            manifestStamp = stamp;
        }
        manifestCheckedAt = now;
        return manifestCache;
    }

    private static long stamp(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return -1L;
            }
            return Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private Map<String, DimClaims> claimsFor(StackLayer layer) {
        Path file = layer.metaDir(worldRoot).resolve("claims").resolve("claims.json");
        long mtime;
        long size;
        try {
            if (!Files.isRegularFile(file)) {
                return Map.of();
            }
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException e) {
            return Map.of();
        }
        ClaimsSnapshot snapshot = claimsCache.get(file);
        if (snapshot != null && snapshot.mtime() == mtime && snapshot.size() == size) {
            return snapshot.byDimension();
        }
        Map<String, List<ClaimNode>> grouped = new LinkedHashMap<>();
        for (ClaimNode node : AreaService.readClaims(file)) {
            String key = node.getDimension().identifier().toString();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
        Map<String, DimClaims> byDimension = new HashMap<>();
        for (Map.Entry<String, List<ClaimNode>> entry : grouped.entrySet()) {
            Set<ChunkCoord> nodeSet = new HashSet<>();
            List<int[]> segments = new ArrayList<>();
            for (ClaimNode node : entry.getValue()) {
                nodeSet.add(node.getLocation());
                segments.addAll(node.getShapeSegments());
            }
            byDimension.put(entry.getKey(), new DimClaims(entry.getValue(),
                    Geometry.compute(nodeSet, segments)));
        }
        claimsCache.put(file, new ClaimsSnapshot(mtime, size, Map.copyOf(byDimension)));
        return byDimension;
    }

    private static List<int[]> listRegions(Path dir) {
        List<int[]> regions = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return regions;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                Matcher matcher = REGION_FILE.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    regions.add(new int[]{Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))});
                }
            });
        } catch (IOException e) {
            Samsara.LOGGER.debug("[web] 读取 region 目录失败 {}: {}", dir, e.getMessage());
        }
        regions.sort(Comparator.comparingInt((int[] value) -> value[0])
                .thenComparingInt(value -> value[1]));
        return regions;
    }

    private static Path regionDir(Path world, StackLayer layer, String shortDim) {
        Path dir = StackPaths.layerDimension(world, layer, shortDim).resolve("region");
        if (Files.isDirectory(dir)) {
            return dir;
        }
        // 旧存档/首次升级：主世界 region 可能还在 world/region。
        if (layer.top && "overworld".equals(shortDim)) {
            Path legacy = world.resolve("region");
            if (Files.isDirectory(legacy)) {
                return legacy;
            }
        }
        return null;
    }

    private static String shortDimension(StackLayer layer, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        String value = dimension.trim();
        for (String dim : DIMENSIONS) {
            if (dim.equals(value)) {
                return dim;
            }
            if (StackPaths.key(layer, dim).identifier().toString().equals(value)) {
                return dim;
            }
        }
        return null;
    }

    private static String relative(Path world, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return world.relativize(path.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }

    private static Map<String, Object> claimRangeModel() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ClaimRangeConfig config = ClaimRangeConfig.getInstance();
            out.put("mode", config.mode().name().toLowerCase(java.util.Locale.ROOT));
            out.put("fixedDistance", config.fixedDistance());
            out.put("colorDistances", config.colorDistances());
            out.put("maxDistance", config.maxDistance());
            out.put("patternDirections", config.patternDirections());
        } catch (RuntimeException e) {
            // 单测/配置尚未初始化时退回默认值，地图本身仍可打开。
            out.put("mode", "dye");
            out.put("fixedDistance", ClaimRangeConfig.DEFAULT_FIXED_DISTANCE);
            out.put("colorDistances", ClaimRangeConfig.DEFAULT_COLOR_DISTANCES);
            out.put("maxDistance", ClaimRangeConfig.DEFAULT_MAX_DISTANCE);
            out.put("patternDirections", ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS);
        }
        return out;
    }

    static String dimensionName(String id) {
        return switch (id) {
            case "overworld" -> "主世界";
            case "the_nether" -> "下界";
            case "the_end" -> "末地";
            default -> id;
        };
    }

    private record DimClaims(List<ClaimNode> nodes, Set<ChunkCoord> protectedChunks) {
    }

    private record ClaimsSnapshot(long mtime, long size, Map<String, DimClaims> byDimension) {
    }
}
