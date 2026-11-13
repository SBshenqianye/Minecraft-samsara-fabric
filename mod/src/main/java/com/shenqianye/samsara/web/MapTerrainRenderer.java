package com.shenqianye.samsara.web;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 把一个 region 文件渲染成地形小地图 PNG。
 *
 * <p>只读存档：按 {@code Heightmaps.WORLD_SURFACE}（回退 MOTION_BLOCKING_NO_LEAVES）
 * 找到每列最高的非空气方块，取它的原版地图色，再按南北/东西一格高度差做轻微明暗。渲染结果按 region/缩放级别缓存，
 * 不写世界数据，也不加载区块到服务器内存。
 *
 * <p>坐标约定：图像左上角 = 区块西北角；x 向右（东），z 向下（南）。
 */
final class MapTerrainRenderer {
    static final int REGION_BLOCKS = 512;
    private static final int CHUNK_BLOCKS = 16;
    private static final int HEIGHTMAP_BITS = 9;
    private static final int MAX_SCAN_DOWN = 384;
    private static final int MISSING_HEIGHT = Integer.MIN_VALUE / 4;
    private static final int FALLBACK_COLOR = 0x5b6676;
    private static final RegionStorageInfo STORAGE =
            new RegionStorageInfo("samsara-map", Level.OVERWORLD, "region");
    private static final Map<String, Integer> BLOCK_COLORS = new ConcurrentHashMap<>();

    private MapTerrainRenderer() {
    }

    /**
     * 读取单个方块列的地表 Y（脚下方块的上方一格），用于出生点传送命令。
     *
     * <p>只读一个区块的 NBT，不加载区块；区块未生成/高度图缺失时返回 null。
     */
    static Integer surfaceY(Path regionDir, int blockX, int blockZ) throws IOException {
        int regionX = Math.floorDiv(blockX, REGION_BLOCKS);
        int regionZ = Math.floorDiv(blockZ, REGION_BLOCKS);
        Path file = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        ChunkPos pos = new ChunkPos(Math.floorDiv(blockX, CHUNK_BLOCKS),
                Math.floorDiv(blockZ, CHUNK_BLOCKS));
        try (RegionFile region = new RegionFile(STORAGE, file, regionDir, false);
             DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) {
                return null;
            }
            Surface surface = surfaceOf(net.minecraft.nbt.NbtIo.read(in));
            int height = surface.heights[localIndex(blockX, blockZ)];
            return height == MISSING_HEIGHT ? null : height + 1;
        }
    }

    /** 渲染一个 region 的指定缩放级别；scale = 每个像素代表多少方块。 */
    static byte[] render(Path regionDir, int regionX, int regionZ, int scale, boolean shading)
            throws IOException {
        Surface[][] surfaces = new Surface[32][32];
        Path file = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (RegionFile region = new RegionFile(STORAGE, file, regionDir, false)) {
            for (int cz = 0; cz < 32; cz += 1) {
                for (int cx = 0; cx < 32; cx += 1) {
                    ChunkPos pos = new ChunkPos(regionX * 32 + cx, regionZ * 32 + cz);
                    try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                        if (in == null) {
                            continue;
                        }
                        surfaces[cx][cz] = surfaceOf(net.minecraft.nbt.NbtIo.read(in));
                    } catch (IOException e) {
                        // 单个区块损坏不拖垮整张图；该区块显示为空白。
                    }
                }
            }
        }

        int size = REGION_BLOCKS / scale;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int py = 0; py < size; py += 1) {
            int bz = regionZ * REGION_BLOCKS + py * scale + scale / 2;
            int row = py * size;
            for (int px = 0; px < size; px += 1) {
                int bx = regionX * REGION_BLOCKS + px * scale + scale / 2;
                int color = colorAt(surfaces, regionX, regionZ, bx, bz);
                pixels[row + px] = shading
                        ? shadeAt(surfaces, regionX, regionZ, bx, bz, color)
                        : color;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 扫描 region 文件里的下界/末地传送门；不加载区块，用于地图 POI 的一次性全图索引。 */
    static List<PortalPos> scanPortals(Path regionDir) throws IOException {
        List<PortalPos> out = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(regionDir)) {
            files = stream.filter(path -> path.getFileName().toString()
                    .matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String[] parts = name.substring(2, name.length() - 4).split("\\.");
            if (parts.length != 2) continue;
            int regionX;
            int regionZ;
            try {
                regionX = Integer.parseInt(parts[0]);
                regionZ = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            try (RegionFile region = new RegionFile(STORAGE, file, regionDir, false)) {
                for (int cz = 0; cz < 32; cz += 1) {
                    for (int cx = 0; cx < 32; cx += 1) {
                        ChunkPos pos = new ChunkPos(regionX * 32 + cx, regionZ * 32 + cz);
                        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                            if (in == null) continue;
                            scanPortalChunk(net.minecraft.nbt.NbtIo.read(in),
                                    pos.getMinBlockX(), pos.getMinBlockZ(), out);
                        } catch (IOException | RuntimeException e) {
                            // 单区块损坏不阻塞全图扫描。
                        }
                    }
                }
            } catch (IOException e) {
                // region 文件损坏/被占用时跳过。
            }
        }
        return out;
    }

    private static void scanPortalChunk(CompoundTag chunk, int baseX, int baseZ,
                                        List<PortalPos> out) {
        Map<Integer, Section> sections = new HashMap<>();
        ListTag sectionTags = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sectionTags.size(); i += 1) {
            CompoundTag sectionTag = sectionTags.getCompoundOrEmpty(i);
            int y = sectionTag.getIntOr("Y", 0);
            Section section = Section.from(sectionTag);
            if (section.hasPortal()) {
                sections.put(y, section);
            }
        }
        for (Map.Entry<Integer, Section> entry : sections.entrySet()) {
            int sectionY = entry.getKey();
            Section section = entry.getValue();
            int y0 = sectionY << 4;
            for (int ly = 0; ly < 16; ly += 1) {
                for (int lz = 0; lz < 16; lz += 1) {
                    for (int lx = 0; lx < 16; lx += 1) {
                        String id = section.blockAt(lx, ly, lz);
                        if ("minecraft:nether_portal".equals(id)) {
                            out.add(new PortalPos("nether", baseX + lx, y0 + ly, baseZ + lz));
                        } else if ("minecraft:end_portal".equals(id)) {
                            out.add(new PortalPos("end", baseX + lx, y0 + ly, baseZ + lz));
                        }
                    }
                }
            }
        }
    }

    record PortalPos(String kind, int x, int y, int z) {
    }

    private static int colorAt(Surface[][] surfaces, int regionX, int regionZ, int bx, int bz) {
        Surface surface = surfaceAt(surfaces, regionX, regionZ, bx, bz);
        if (surface == null) {
            return 0xff111827;
        }
        int index = localIndex(bx, bz);
        return 0xff000000 | surface.colors[index];
    }

    private static int shadeAt(Surface[][] surfaces, int regionX, int regionZ,
                               int bx, int bz, int argb) {
        Surface current = surfaceAt(surfaces, regionX, regionZ, bx, bz);
        if (current == null) {
            return argb;
        }
        int index = localIndex(bx, bz);
        int height = current.heights[index];
        if (height == MISSING_HEIGHT || current.waters[index]) {
            return argb;
        }
        Surface north = surfaceAt(surfaces, regionX, regionZ, bx, bz - 1);
        Surface west = surfaceAt(surfaces, regionX, regionZ, bx - 1, bz);
        double factor = 1.0;
        if (north != null) {
            int nh = north.heights[localIndex(bx, bz - 1)];
            factor *= nh > height ? 1.05 : nh < height ? 0.95 : 1.0;
        }
        if (west != null) {
            int wh = west.heights[localIndex(bx - 1, bz)];
            factor *= wh > height ? 1.02 : wh < height ? 0.98 : 1.0;
        }
        return multiply(argb, factor);
    }

    private static int multiply(int argb, double factor) {
        int r = Math.max(0, Math.min(255, (int) Math.round(((argb >> 16) & 0xff) * factor)));
        int g = Math.max(0, Math.min(255, (int) Math.round(((argb >> 8) & 0xff) * factor)));
        int b = Math.max(0, Math.min(255, (int) Math.round((argb & 0xff) * factor)));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static Surface surfaceAt(Surface[][] surfaces, int regionX, int regionZ, int bx, int bz) {
        int localX = bx - regionX * REGION_BLOCKS;
        int localZ = bz - regionZ * REGION_BLOCKS;
        if (localX < 0 || localZ < 0 || localX >= REGION_BLOCKS || localZ >= REGION_BLOCKS) {
            return null;
        }
        return surfaces[localX / CHUNK_BLOCKS][localZ / CHUNK_BLOCKS];
    }

    private static int localIndex(int bx, int bz) {
        return (bz & 15) * CHUNK_BLOCKS + (bx & 15);
    }

    private static Surface surfaceOf(CompoundTag chunk) {
        int[] heights = new int[256];
        int[] colors = new int[256];
        boolean[] waters = new boolean[256];
        java.util.Arrays.fill(heights, MISSING_HEIGHT);
        java.util.Arrays.fill(colors, FALLBACK_COLOR);

        Map<Integer, Section> sections = new HashMap<>();
        ListTag sectionTags = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sectionTags.size(); i += 1) {
            CompoundTag sectionTag = sectionTags.getCompoundOrEmpty(i);
            int y = sectionTag.getIntOr("Y", 0);
            sections.put(y, Section.from(sectionTag));
        }
        int minSectionY = sections.keySet().stream().min(Integer::compareTo).orElse(-4);
        int maxSectionY = sections.keySet().stream().max(Integer::compareTo).orElse(19);
        int minY = minSectionY * 16;
        int maxY = maxSectionY * 16 + 15;

        long[] heightmap = chunk.getCompoundOrEmpty("Heightmaps")
                // WORLD_SURFACE 包含树叶/植被；MOTION_BLOCKING_NO_LEAVES 会让树林在地图上变秃。
                .getLongArray("WORLD_SURFACE")
                .orElseGet(() -> chunk.getCompoundOrEmpty("Heightmaps")
                        .getLongArray("MOTION_BLOCKING_NO_LEAVES").orElse(null));
        if (heightmap == null) {
            return new Surface(heights, colors, waters);
        }

        for (int index = 0; index < 256; index += 1) {
            int lx = index & 15;
            int lz = index >> 4;
            int top = (int) unpack(heightmap, HEIGHTMAP_BITS, index) - 1;
            if (top < minY || top > maxY) {
                // 高度图为 0/越界时说明该列没有可用记录，从世界顶部重新找。
                top = maxY;
            }
            String blockId = blockAt(sections, lx, top, lz);
            int y = top;
            // 测试/编辑过的存档可能留下过期高度图；向下最多扫完整个世界高度兜底。
            for (int step = 0; step < MAX_SCAN_DOWN && isAir(blockId); step += 1) {
                y -= 1;
                blockId = blockAt(sections, lx, y, lz);
            }
            if (isAir(blockId)) {
                continue;
            }
            // 部分存档/高度图会把树冠排除在外，只留下树干。向上找最近的树叶，
            // 让树林在地图上显示为连续的绿色树冠，而不是孤立的棕色树干点。
            int leafY = -1;
            for (int up = 1; up <= 16; up += 1) {
                String above = blockAt(sections, lx, y + up, lz);
                if (isLeaves(above)) {
                    leafY = y + up;
                }
            }
            if (leafY > y) {
                y = leafY;
                blockId = blockAt(sections, lx, y, lz);
            }
            heights[index] = y;
            colors[index] = colorOf(blockId);
            waters[index] = "minecraft:water".equals(blockId);
        }
        return new Surface(heights, colors, waters);
    }

    private static String blockAt(Map<Integer, Section> sections, int lx, int y, int lz) {
        if (y < -512 || y > 1024) {
            return "minecraft:air";
        }
        Section section = sections.get(y >> 4);
        if (section == null) {
            return "minecraft:air";
        }
        return section.blockAt(lx, y & 15, lz);
    }

    private static boolean isAir(String id) {
        return id == null || "minecraft:air".equals(id)
                || "minecraft:cave_air".equals(id) || "minecraft:void_air".equals(id);
    }

    private static boolean isLeaves(String id) {
        return id != null && (id.endsWith("_leaves") || id.equals("minecraft:azalea_leaves")
                || id.equals("minecraft:flowering_azalea_leaves"));
    }

    private static int colorOf(String id) {
        return BLOCK_COLORS.computeIfAbsent(id, key -> {
            Identifier identifier = Identifier.tryParse(key);
            if (identifier == null) {
                return FALLBACK_COLOR;
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
            if (block == null) {
                return FALLBACK_COLOR;
            }
            MapColor mapColor = block.defaultMapColor();
            if (mapColor == null || mapColor == MapColor.NONE || mapColor.col == 0) {
                return FALLBACK_COLOR;
            }
            int color = mapColor.col & 0xffffff;
            // 树叶与草地方块的原版地图色相同，森林会糊成一片；树冠略微压暗才能看出树林。
            if (key.endsWith("_leaves")) {
                color = multiplyColor(color, 0.72);
            }
            return color;
        });
    }

    private static int multiplyColor(int rgb, double factor) {
        int r = Math.max(0, Math.min(255, (int) Math.round(((rgb >> 16) & 0xff) * factor)));
        int g = Math.max(0, Math.min(255, (int) Math.round(((rgb >> 8) & 0xff) * factor)));
        int b = Math.max(0, Math.min(255, (int) Math.round((rgb & 0xff) * factor)));
        return (r << 16) | (g << 8) | b;
    }

    private static long unpack(long[] values, int bits, int index) {
        // MC 26.3 的 PalettedContainer / Heightmap 使用“每个 long 内不跨边界”的
        // 打包方式：64 / bits 个值占一个 long，剩余位留空。
        int perLong = 64 / bits;
        int longIndex = index / perLong;
        if (longIndex < 0 || longIndex >= values.length) {
            return 0;
        }
        int offset = (index % perLong) * bits;
        return (values[longIndex] >>> offset) & ((1L << bits) - 1);
    }

    private record Surface(int[] heights, int[] colors, boolean[] waters) {
    }

    private record Section(java.util.List<String> palette, long[] data, int bits) {
        static Section from(CompoundTag sectionTag) {
            CompoundTag states = sectionTag.getCompoundOrEmpty("block_states");
            ListTag paletteTag = states.getListOrEmpty("palette");
            java.util.List<String> palette = new java.util.ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i += 1) {
                palette.add(paletteName(paletteTag.get(i)));
            }
            long[] data = states.getLongArray("data").orElse(null);
            int bits = palette.size() <= 1 ? 0
                    : Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            return new Section(java.util.List.copyOf(palette), data, bits);
        }

        String blockAt(int lx, int ly, int lz) {
            if (palette.isEmpty()) {
                return "minecraft:air";
            }
            if (bits == 0 || data == null) {
                return palette.get(0);
            }
            int index = (ly << 8) | (lz << 4) | lx;
            int value = (int) unpack(data, bits, index);
            return palette.get(Math.min(value, palette.size() - 1));
        }

        boolean hasPortal() {
            for (String id : palette) {
                if ("minecraft:nether_portal".equals(id) || "minecraft:end_portal".equals(id)) {
                    return true;
                }
            }
            return false;
        }

        private static String paletteName(Tag tag) {
            return tag.asString().orElseGet(() -> tag.asCompound()
                    .map(c -> c.getStringOr("Name", "minecraft:air"))
                    .orElse("minecraft:air"));
        }
    }
}
