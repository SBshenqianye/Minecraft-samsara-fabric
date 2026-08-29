package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
     * 零拷贝快速清零：直接 seek 到 slot 位置写 8 字节零（offset 4B + timestamp 4B），
     * 不读 header。每个 MCA 文件只 open 一次。比 deleteSlots 快 ~2x。
     * <p>
     * 幂等：已清零的 slot 再写零无副作用。
     *
     * @return 实际清零的槽数（文件不存在返回 0）
     */
    public static int zeroSlots(Path file, List<Integer> slots) {
        if (slots.isEmpty() || !Files.exists(file)) {
            return 0;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            long len = raf.length();
            if (len < HEADER_SIZE) return 0;
            byte[] zeros = new byte[8]; // 4B offset + 4B timestamp
            int deleted = 0;
            for (int slot : slots) {
                int off = slot * 4;
                int toff = HEADER_SIZE / 2 + off;
                // 直接写零，不读不检查
                raf.seek(off);
                raf.write(zeros, 0, 4);
                raf.seek(toff);
                raf.write(zeros, 0, 4);
                deleted++;
            }
            return deleted;
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] 快速清零失败 {}: {}", file, e.getMessage());
            return 0;
        }
    }

    /**
     * 删除指定 slot 的区块（位置 + 时间戳清零）。返回实际清零的槽数。
     * 保留用于需要检查 sectorOffset 的场景（如单块精确删除）。
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

    /**
     * 批量删除区块（无索引版本，向后兼容）。
     */
    public static int deleteChunks(Path dimRoot, List<int[]> chunks) {
        return deleteChunks(dimRoot, chunks, null);
    }

    /**
     * 批量删除区块：按文件分组，利用白名单索引快速决策。
     * <ul>
     *   <li>索引显示该文件白名单数=0 → 整文件秒删（不读 header）</li>
     *   <li>索引显示白名单数=N（混合文件）→ 读一次 header，只清零非白名单 slot</li>
     *   <li>无索引 → 退化为读 header 统计</li>
     * </ul>
     *
     * @param whitelistCounts 可选索引：region 文件名 → 白名单区块数（null=无索引）
     * @return 实际处理的区块数
     */
    public static int deleteChunks(Path dimRoot, List<int[]> chunks, Map<String, Integer> whitelistCounts) {
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
            Path file = e.getKey();
            List<Integer> slots = e.getValue();
            if (!Files.exists(file)) continue;

            String fileName = file.getFileName().toString();
            Integer wlCount = whitelistCounts != null ? whitelistCounts.getOrDefault(fileName, 0) : null;

            if (wlCount != null && wlCount == 0) {
                // ★ 索引命中：该文件 0 个白名单区块 → 整文件秒删
                try {
                    int estimated = countOccupiedSlots(file);
                    Files.deleteIfExists(file);
                    Samsara.LOGGER.info("[周目管理 samsara] 索引快删（{} 区块）: {}", estimated, fileName);
                    total += estimated;
                } catch (IOException ex) {
                    Samsara.LOGGER.warn("[周目管理 samsara] 索引快删失败 {}: {}", fileName, ex.getMessage());
                }
            } else {
                // 混合文件或无索引：读 header → 按情况处理
                byte[] header = readHeader(file);
                if (header == null) continue;

                int totalSlots = countOccupiedInHeader(header);

                if (totalSlots <= slots.size()) {
                    // 所有区块都在删除列表 → 整文件删
                    try {
                        Files.deleteIfExists(file);
                        Samsara.LOGGER.info("[周目管理 samsara] 全文件删除（{} 区块）: {}", totalSlots, fileName);
                        total += totalSlots;
                    } catch (IOException ex) {
                        Samsara.LOGGER.warn("[周目管理 samsara] 全文件删除失败 {}: {}", fileName, ex.getMessage());
                    }
                } else {
                    // 混合：只清零对应 slot
                    int deleted = zeroSlotsFromHeader(header, file, slots);
                    total += deleted;
                }
            }
        }
        return total;
    }

    /** 统计 header 中已占用的 slot 数（非零 offset） */
    private static int countOccupiedInHeader(byte[] header) {
        int count = 0;
        for (int s = 0; s < 1024; s++) {
            int off = s * 4;
            if ((((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF)) != 0) {
                count++;
            }
        }
        return count;
    }

    /** 快速统计文件中已占用 slot 数（读一次 header） */
    private static int countOccupiedSlots(Path file) {
        byte[] header = readHeader(file);
        return header != null ? countOccupiedInHeader(header) : 0;
    }

    /**
     * 用已读取的 header 直接清零指定 slot（不重新读文件）。
     * 比 deleteSlots 快：省掉 readHeader 的 4KB 读取。
     */
    private static int zeroSlotsFromHeader(byte[] header, Path file, List<Integer> slots) {
        byte[] zeros = new byte[4];
        int deleted = 0;
        for (int slot : slots) {
            int off = slot * 4;
            int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
            if (sectorOffset != 0) {
                header[off] = 0; header[off + 1] = 0; header[off + 2] = 0; header[off + 3] = 0;
                int toff = HEADER_SIZE / 2 + slot * 4;
                header[toff] = 0; header[toff + 1] = 0; header[toff + 2] = 0; header[toff + 3] = 0;
                deleted++;
            }
        }
        if (deleted > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.seek(0);
                raf.write(header, 0, HEADER_SIZE);
            } catch (IOException e) {
                Samsara.LOGGER.error("[周目管理 samsara] 写回区域文件失败 {}: {}", file, e.getMessage());
            }
        }
        return deleted;
    }

    /** 判断 .mca 是否已无任何有效区块（1024 个位置槽全部为 0） */
    public static boolean isFullyEmpty(Path file) {
        byte[] header = readHeader(file);
        if (header == null) {
            return false;
        }
        for (int slot = 0; slot < 1024; slot++) {
            int off = slot * 4;
            int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
            if (sectorOffset != 0) {
                return false;
            }
        }
        return true;
    }

    /** 全空则删除文件（尽力而为；文件被占用时跳过） */
    public static boolean deleteIfFullyEmpty(Path file) {
        if (!Files.exists(file) || !isFullyEmpty(file)) {
            return false;
        }
        try {
            Files.deleteIfExists(file);
            Samsara.LOGGER.info("[周目管理 samsara] 全空区域文件已删除: {}", file.getFileName());
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 删除全空区域文件失败（可能被占用，停服窗口重试）: {}", e.getMessage());
            return false;
        }
    }

    /** 压缩统计 */
    public record CompactStats(int filesScanned, int filesDeleted, int filesRewritten, long bytesFreed) {
    }

    /**
     * 区域文件瘦身（设计规范 §12 文件级回收）：扫描 region/entities/poi 全部 .mca ——
     * 全空文件直接删除；部分有效文件先备份再重写为「只含有效区块」的紧凑文件
     * （丢弃孤儿数据扇区，真正回收磁盘空间）。
     * <p>
     * 应在停服窗口（区域文件已关闭）调用；运行中调用为尽力而为（被占用文件跳过）。
     *
     * @param dimRoot     主世界维度目录（…/overworld）
     * @param backupsRoot 备份根目录（…/backups），压缩前备份到 backups/compact_&lt;时间戳&gt;/
     */
    public static CompactStats compact(Path dimRoot, Path backupsRoot) {
        String ts = java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupDir = backupsRoot.resolve("compact_" + ts).resolve("dimensions").resolve("minecraft")
                .resolve("overworld");
        int scanned = 0;
        int deleted = 0;
        int rewritten = 0;
        long freed = 0;
        for (String dir : new String[]{"region", "entities", "poi"}) {
            Path d = dimRoot.resolve(dir);
            for (Path f : listRegionFiles(d)) {
                scanned++;
                long size;
                try {
                    size = Files.size(f);
                } catch (IOException e) {
                    continue;
                }
                byte[] header = readHeader(f);
                if (header == null) {
                    continue;
                }
                // 收集有效槽 {slot, sectorOffset, sectorCount}
                List<int[]> valid = new ArrayList<>();
                for (int slot = 0; slot < 1024; slot++) {
                    int off = slot * 4;
                    int so = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
                    if (so != 0) {
                        valid.add(new int[]{slot, so, header[off + 3] & 0xFF});
                    }
                }
                if (valid.isEmpty()) {
                    if (deleteIfFullyEmpty(f)) {
                        deleted++;
                        freed += size;
                    }
                    continue;
                }
                // 压缩重写：只保留有效区块数据
                long totalSectors = 0;
                for (int[] v : valid) {
                    totalSectors += v[2];
                }
                if (totalSectors > 100_000) { // >400MB 单文件保护，跳过
                    Samsara.LOGGER.warn("[周目管理 samsara] 压缩跳过超大文件 {}（{} 扇区）", f, totalSectors);
                    continue;
                }
                try {
                    byte[] packed = new byte[8192 + (int) (totalSectors * 4096)];
                    int cursor = 2; // 数据扇区从 2 开始（0~1 为头部）
                    try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                        for (int[] v : valid) {
                            raf.seek((long) v[1] * 4096);
                            raf.readFully(packed, cursor * 4096, v[2] * 4096);
                            int ho = v[0] * 4;
                            packed[ho] = (byte) (cursor >>> 16);
                            packed[ho + 1] = (byte) (cursor >>> 8);
                            packed[ho + 2] = (byte) cursor;
                            packed[ho + 3] = (byte) v[2];
                            System.arraycopy(header, 4096 + v[0] * 4, packed, 4096 + v[0] * 4, 4);
                            cursor += v[2];
                        }
                    }
                    // 备份原文件 → 写临时文件 → 替换
                    Path dst = backupDir.resolve(dir).resolve(f.getFileName().toString());
                    Files.createDirectories(dst.getParent());
                    Files.copy(f, dst, StandardCopyOption.REPLACE_EXISTING);
                    Path tmp = f.resolveSibling(f.getFileName() + ".compact.tmp");
                    Files.write(tmp, packed);
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                    freed += size - packed.length;
                    rewritten++;
                } catch (IOException e) {
                    Samsara.LOGGER.warn("[周目管理 samsara] 压缩 {} 失败（可能被占用）: {}", f, e.getMessage());
                }
            }
        }
        return new CompactStats(scanned, deleted, rewritten, Math.max(0, freed));
    }

    /** 区块是否仍存在于磁盘（region 槽位置非 0）。用于幂等删除：已清零的区块不再重复备份/删除。 */
    public static boolean chunkExists(Path dimRoot, int cx, int cz) {
        RegionPos rp = RegionPos.ofChunk(cx, cz);
        Path f = dimRoot.resolve("region").resolve("r." + rp.rx() + "." + rp.rz() + ".mca");
        byte[] header = readHeader(f);
        if (header == null) {
            return false;
        }
        int localX = cx - rp.rx() * 32;
        int localZ = cz - rp.rz() * 32;
        int slot = localZ * 32 + localX;
        int off = slot * 4;
        int sectorOffset = ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
        return sectorOffset != 0;
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