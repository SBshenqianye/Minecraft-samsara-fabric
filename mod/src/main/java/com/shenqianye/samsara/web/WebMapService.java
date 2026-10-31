package com.shenqianye.samsara.web;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.claim.ClaimRangeConfig;
import com.shenqianye.samsara.claim.GeometryStore;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 地图标签的数据服务：扫描存档 region 文件 + 读取圈地白名单。
 *
 * <p>地形底图由 {@link MapTerrainRenderer} 按需渲染成 PNG；这里只负责：
 * 列出维度/region、汇总圈地保护区块与旗帜、维护有限大小的 tile 缓存。
 * 不写存档，也不把区块加载进服务器内存。
 */
public class WebMapService {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Set<Integer> SCALES = Set.of(1, 2, 4, 8);
    private static final int MAX_CACHE = 96;

    private final Map<String, byte[]> tiles = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    /** 返回地图页需要的维度、region 列表、保护区块和旗帜。 */
    public Map<String, Object> model() {
        Map<String, Path> regionDirs = discoverRegionDirs();
        Map<String, DimClaims> claims = collectClaims();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("worldRoot", worldRoot().toString());
        out.put("generatedAt", System.currentTimeMillis());
        out.put("claimRange", claimRangeModel());
        List<Map<String, Object>> dimensions = new ArrayList<>();

        List<String> ids = new ArrayList<>(regionDirs.keySet());
        for (String id : claims.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparingInt(WebMapService::dimensionOrder).thenComparing(id -> id));

        for (String id : ids) {
            Path dir = regionDirs.get(id);
            DimClaims dimClaims = claims.get(id);
            dimensions.add(dimensionModel(id, dir, dimClaims));
        }
        out.put("dimensions", dimensions);
        return out;
    }

    /** 返回一个 region 的地形 PNG；维度/坐标/缩放非法或文件不存在时返回 null。 */
    public byte[] tile(String dimension, int regionX, int regionZ, int scale) {
        return tile(dimension, regionX, regionZ, scale, true);
    }

    public byte[] tile(String dimension, int regionX, int regionZ, int scale, boolean shading) {
        if (!SCALES.contains(scale)) {
            return null;
        }
        Path dir = discoverRegionDirs().get(dimension);
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            String key = dimension + "|" + regionX + "|" + regionZ + "|" + scale
                    + "|" + shading + "|" + modified;
            byte[] cached = tiles.get(key);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = MapTerrainRenderer.render(dir, regionX, regionZ, scale, shading);
            if (bytes == null) {
                return null;
            }
            tiles.put(key, bytes);
            // 旧 mtime 的同一 tile 不再需要。
            String prefix = dimension + "|" + regionX + "|" + regionZ + "|" + scale + "|";
            synchronized (tiles) {
                tiles.keySet().removeIf(existing -> existing.startsWith(prefix)
                        && !existing.equals(key));
            }
            return bytes;
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.warn("[web] 地图 tile 渲染失败 {}/{},{}: {}",
                    dimension, regionX, regionZ, e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> claimRangeModel() {
        ClaimRangeConfig config = ClaimRangeConfig.getInstance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", config.mode().name().toLowerCase(java.util.Locale.ROOT));
        out.put("fixedDistance", config.fixedDistance());
        out.put("colorDistances", config.colorDistances());
        out.put("maxDistance", config.maxDistance());
        out.put("patternDirections", config.patternDirections());
        return out;
    }

    private Map<String, Object> dimensionModel(String id, Path dir, DimClaims claims) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", dimensionName(id));

        List<int[]> regions = new ArrayList<>();
        if (dir != null) {
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
        }
        regions.sort(Comparator.comparingInt((int[] value) -> value[0])
                .thenComparingInt(value -> value[1]));

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

        out.put("regions", regions);
        out.put("protectedChunks", protectedChunks);
        out.put("banners", banners);
        out.put("claimNodes", claims == null ? 0 : claims.nodes().size());
        out.put("protectedCount", protectedChunks.size());
        out.put("bannerCount", banners.size());
        out.put("bounds", bounds(regions, protectedChunks, banners));
        return out;
    }

    private static Map<String, Object> bounds(List<int[]> regions, List<int[]> chunks,
                                               List<Map<String, Object>> banners) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // 初始视图优先套住圈地；没有圈地时才退回 region 范围。
        // region 文件可能包含测试/远航产生的孤立区块，不能用来代表“服务器地图范围”。
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
            for (int[] region : regions) {
                minX = Math.min(minX, region[0] * MapTerrainRenderer.REGION_BLOCKS);
                minZ = Math.min(minZ, region[1] * MapTerrainRenderer.REGION_BLOCKS);
                maxX = Math.max(maxX, (region[0] + 1) * MapTerrainRenderer.REGION_BLOCKS);
                maxZ = Math.max(maxZ, (region[1] + 1) * MapTerrainRenderer.REGION_BLOCKS);
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return Map.of("minX", 0, "minZ", 0, "maxX", 0, "maxZ", 0);
        }
        return Map.of("minX", minX, "minZ", minZ, "maxX", maxX, "maxZ", maxZ);
    }

    private Map<String, DimClaims> collectClaims() {
        Map<String, DimClaims> out = new TreeMap<>();
        try {
            AreaService area = AreaService.getInstance();
            GeometryStore geometry = GeometryStore.getInstance();
            for (Map.Entry<ResourceKey<Level>, Map<ChunkCoord, ClaimNode>> entry
                    : area.getAllNodes().entrySet()) {
                String id = entry.getKey().identifier().toString();
                out.put(id, new DimClaims(new ArrayList<>(entry.getValue().values()),
                        geometry.getWhitelist(entry.getKey())));
            }
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[web] 读取圈地数据失败: {}", e.getMessage());
        }
        return out;
    }

    private static Map<String, Path> discoverRegionDirs() {
        Map<String, Path> out = new LinkedHashMap<>();
        Path root = worldRoot().resolve("region");
        if (Files.isDirectory(root)) {
            out.put(Level.OVERWORLD.identifier().toString(), root);
        }
        Path dimensions = worldRoot().resolve("dimensions");
        if (!Files.isDirectory(dimensions)) {
            return out;
        }
        try (Stream<Path> namespaces = Files.list(dimensions)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                try (Stream<Path> paths = Files.list(namespace)) {
                    for (Path dimension : paths.filter(Files::isDirectory).toList()) {
                        Path region = dimension.resolve("region");
                        if (Files.isDirectory(region)) {
                            String id = namespace.getFileName() + ":"
                                    + dimension.getFileName();
                            out.put(id, region);
                        }
                    }
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.debug("[web] 扫描维度目录失败: {}", e.getMessage());
        }
        return out;
    }

    private static Path worldRoot() {
        return StoragePaths.get().worldRoot();
    }

    private static int dimensionOrder(String id) {
        return switch (id) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 10;
        };
    }

    static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> id;
        };
    }

    private record DimClaims(List<ClaimNode> nodes, Set<ChunkCoord> protectedChunks) {
    }
}
