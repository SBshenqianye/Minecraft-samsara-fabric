package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCA 区域文件读写（M2 档2 倒流用；仅主世界 region/、entities/、poi/，格式与 MC
 * 1.14+ 一致，26.2 无变化）。
 * <p>
 * MCA 格式要点：文件头 8192 字节 = 4096（区块位置 4B×1024，3B 扇区偏移 + 1B 扇区数）
 * + 4096（时间戳 4B×1024）。slot = localZ*32 + localX（local = chunk 坐标 mod 32，
 * 负坐标按 MC 规则为正偏移：cx - rx*32）。
 * <p>
 * 删除区块 = 把位置/时间戳清零（区块数据扇区成为孤儿，MC 加载时视为空区块重新生成）；
 * 全版本通用、不依赖 MC 内部类，仅在停服窗口（服务器已保存并关闭文件）执行。
 */
public class McaRegion {
    private static final int HEADER_SIZE = 8192;

    private McaRegion() {
    }

    /** 区域文件坐标 */
    public record RegionPos(int rx, int rz) {
        public static RegionPos ofChunk(int cx, int cz) {
            return new RegionPos(Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
        }
    }

    /** 扫描一个 region 文件，返回存在的区块 [chunkX, chunkZ] 列表 */
    public static List<int[]> scanExisting(Path file) {
        List<int[]> result = new ArrayList<>();
        byte[] header = readHeader(file);
        if (header == null) {
            return result;
        }
        RegionPos rp = parseRegionName(file.getFileName().toString());
        if (rp == null) {
            return result;
        }
        for (int slot = 0; slot < 1024; slot++) {
            int off = slot * 4;
            int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
            if (sectorOffset == 0) {
                continue; // 空槽
            }
            int localX = slot % 32;
            int localZ = slot / 32;
            result.add(new int[]{rp.rx() * 32 + localX, rp.rz() * 32 + localZ});
        }
        return result;
    }

    /**
     * 删除指定 slot 的区块（位置 + 时间戳清零）。返回实际清零的槽数。
     * 仅在停服窗口调用（此时 MC 已停止写文件）。
     */
    public static int deleteSlots(Path file, List<Integer> slots) {
        if (slots.isEmpty() || !Files.exists(file)) {
            return 0;
        }
        byte[] header = readHeader(file);
        if (header == null) {
            return 0;
        }
        int deleted = 0;
        for (int slot : slots) {
            int off = slot * 4;
            int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
            if (sectorOffset != 0) {
                header[off] = 0;
                header[off + 1] = 0;
                header[off + 2] = 0;
                header[off + 3] = 0;
                int toff = HEADER_SIZE / 2 + slot * 4;
                header[toff] = 0;
                header[toff + 1] = 0;
                header[toff + 2] = 0;
                header[toff + 3] = 0;
                deleted++;
            }
        }
        if (deleted > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.seek(0);
                raf.write(header, 0, HEADER_SIZE);
            } catch (IOException e) {
                Samsara.LOGGER.error("[周目管理 samsara] 写回区域文件失败 {}: {}", file, e.getMessage());
                return 0;
            }
        }
        return deleted;
    }

    /** 同步一组区块坐标对应的主世界 region/entities/poi 三个区域文件的槽位 */
    public static int deleteChunks(Path dimRoot, List<int[]> chunks) {
        // 按 (目录, 区域文件) 分组
        Map<Path, List<Integer>> byFile = new HashMap<>();
        for (int[] c : chunks) {
            int cx = c[0];
            int cz = c[1];
            RegionPos rp = RegionPos.ofChunk(cx, cz);
            int localX = cx - rp.rx() * 32;
            int localZ = cz - rp.rz() * 32;
            int slot = localZ * 32 + localX;
            String name = "r." + rp.rx() + "." + rp.rz() + ".mca";
            for (String dir : new String[]{"region", "entities", "poi"}) {
                byFile.computeIfAbsent(dimRoot.resolve(dir).resolve(name), k -> new ArrayList<>()).add(slot);
            }
        }
        int total = 0;
        for (Map.Entry<Path, List<Integer>> e : byFile.entrySet()) {
            total += deleteSlots(e.getKey(), e.getValue());
        }
        return total;
    }

    /**
     * 主世界维度目录（26.x 存档结构：world/dimensions/minecraft/overworld）。
     * 各子目录 region/entities/poi 存放区块文件。尺寸为区块粒度。
     */
    public static Path overworldDimensionRoot(Path worldRoot) {
        return worldRoot.resolve("dimensions").resolve("minecraft").resolve("overworld");
    }

    private static byte[] readHeader(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            long size = Files.size(file);
            if (size < HEADER_SIZE) {
                return null; // 损坏/空文件
            }
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                byte[] header = new byte[HEADER_SIZE];
                raf.readFully(header);
                return header;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** 从文件名 r.&lt;rx&gt;.&lt;rz&gt;.mca 解析坐标；失败返回 null */
    public static RegionPos parseRegionName(String fileName) {
        if (fileName == null || !fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
            return null;
        }
        String body = fileName.substring(2, fileName.length() - 4);
        int dot = body.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            int rx = Integer.parseInt(body.substring(0, dot));
            int rz = Integer.parseInt(body.substring(dot + 1));
            return new RegionPos(rx, rz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 列出目录下所有 .mca 文件 */
    public static List<Path> listRegionFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.mca")) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException e) {
            // 忽略
        }
        return out;
    }
}