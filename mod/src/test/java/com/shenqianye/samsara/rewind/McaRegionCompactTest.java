package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域文件瘦身回归：26.3 的 entities/poi 文件最后一段经常不补齐到 4096 整扇区，
 * 旧实现按「扇区数 × 4096」读会 EOFException，导致压缩永远失败。
 * 现在按 MC 自己的读法只看长度前缀，并且没有孤儿扇区时不重写。
 */
class McaRegionCompactTest {
    @TempDir
    Path dir;

    private static final int HEADER = 8192;

    /** 一个区块槽：slot 下标、起始扇区、声明的扇区数、负载 */
    private record Chunk(int slot, int sector, int sectors, byte[] payload) {
    }

    /** 写最小 .mca；文件末尾不补齐，只在最后一块结束后截断 */
    private Path mca(String subdir, String name, Chunk... chunks) throws Exception {
        Path f = dir.resolve(subdir).resolve(name);
        Files.createDirectories(f.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(HEADER);
            long end = HEADER;
            for (Chunk c : chunks) {
                raf.seek(c.slot * 4L);
                raf.write(new byte[]{(byte) (c.sector >>> 16), (byte) (c.sector >>> 8),
                        (byte) c.sector, (byte) c.sectors});
                raf.seek((long) HEADER / 2 + c.slot * 4L);
                raf.writeInt(12345);
                raf.seek((long) c.sector * 4096);
                // 长度前缀 = 压缩类型(1B) + 负载，不含自身 4B
                raf.writeInt(c.payload.length + 1);
                raf.writeByte(2);
                raf.write(c.payload);
                end = Math.max(end, (long) c.sector * 4096 + 4 + 1 + c.payload.length);
            }
            raf.setLength(end);
        }
        return f;
    }

    private static int offsetAt(Path f, int slot) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(slot * 4L);
            byte[] b = new byte[4];
            raf.readFully(b);
            return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
        }
    }

    /** 按 MC 的读法取回负载（长度前缀 + 压缩类型），失败即说明文件结构坏了 */
    private static byte[] readPayload(Path f, int slot) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(slot * 4L);
            byte[] b = new byte[4];
            raf.readFully(b);
            int offset = ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
            assertNotEquals(0, offset, "槽不应被清空");
            raf.seek((long) offset * 4096);
            int length = raf.readInt();
            raf.readUnsignedByte(); // 压缩类型
            byte[] payload = new byte[length - 1];
            raf.readFully(payload);
            return payload;
        }
    }

    private static byte[] payload(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return data;
    }

    @Test
    void unpaddedTailIsNotRewrittenWhenNothingToReclaim() throws Exception {
        byte[] data = payload(600, 1);
        Path f = mca("entities", "r.0.0.mca", new Chunk(0, 2, 1, data));
        long originalSize = Files.size(f);
        assertEquals(HEADER + 4 + 1 + data.length, originalSize, "测试文件末尾必须是不对齐的");

        McaRegion.CompactStats stats = McaRegion.compact(dir, dir.resolve("backups"));

        assertEquals(1, stats.filesScanned());
        assertEquals(0, stats.filesRewritten(), "没有孤儿扇区不应重写（旧实现会 EOF 失败）");
        assertEquals(originalSize, Files.size(f), "原文件保持不变");
        assertArrayEquals(data, readPayload(f, 0));
    }

    @Test
    void orphanSectorsAreDroppedAndUnpaddedTailSurvives() throws Exception {
        byte[] first = payload(700, 2);
        byte[] second = payload(500, 3);
        // 第 1 块在扇区 2，第 2 块在扇区 5（扇区 3、4 是孤儿），最后一块不对齐
        Path f = mca("entities", "r.0.0.mca",
                new Chunk(0, 2, 1, first), new Chunk(1, 5, 1, second));
        long originalSize = Files.size(f);
        assertTrue(originalSize < 6L * 4096, "最后一块不应补齐到扇区末尾");

        McaRegion.CompactStats stats = McaRegion.compact(dir, dir.resolve("backups"));

        assertEquals(1, stats.filesScanned());
        assertEquals(1, stats.filesRewritten(), "孤儿扇区应被回收");
        assertEquals(HEADER + 2L * 4096, Files.size(f), "重写后应为紧凑的整扇区文件");
        assertEquals(2, offsetAt(f, 0));
        assertEquals(3, offsetAt(f, 1));
        assertArrayEquals(first, readPayload(f, 0));
        assertArrayEquals(second, readPayload(f, 1));
        assertTrue(stats.bytesFreed() > 0);
    }

    @Test
    void corruptLengthKeepsOriginalFile() throws Exception {
        byte[] data = payload(400, 4);
        Path f = mca("entities", "r.0.0.mca", new Chunk(0, 2, 1, data));
        // 把长度前缀改成远超声明扇区，模拟写入中断
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(2L * 4096);
            raf.writeInt(99_999);
            raf.setLength(6L * 4096); // 制造可回收空间，逼压缩进入读取分支
        }
        long originalSize = Files.size(f);

        McaRegion.CompactStats stats = McaRegion.compact(dir, dir.resolve("backups"));

        assertEquals(0, stats.filesRewritten(), "长度非法的文件必须跳过，不能写出缺块的紧凑文件");
        assertEquals(originalSize, Files.size(f), "原文件保留");
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(2L * 4096);
            assertEquals(99_999, raf.readInt(), "原文件未被改写");
        }
    }

    @Test
    void multipleFilesKeepTheirOwnChunks() throws Exception {
        byte[] a = payload(300, 5);
        byte[] b = payload(300, 6);
        Path fa = mca("entities", "r.0.0.mca", new Chunk(3, 2, 1, a));
        Path fb = mca("poi", "r.0.0.mca", new Chunk(7, 2, 1, b));
        try (RandomAccessFile raf = new RandomAccessFile(fa.toFile(), "rw")) {
            raf.setLength(8L * 4096); // 孤儿尾扇区 → 会重写
        }
        try (RandomAccessFile raf = new RandomAccessFile(fb.toFile(), "rw")) {
            raf.setLength(8L * 4096);
        }

        McaRegion.CompactStats stats = McaRegion.compact(dir, dir.resolve("backups"));

        assertEquals(2, stats.filesScanned());
        assertEquals(2, stats.filesRewritten());
        assertArrayEquals(a, readPayload(fa, 3));
        assertArrayEquals(b, readPayload(fb, 7));
    }

    @Test
    void logSummaryNeverRendersNullMessage() {
        assertEquals("IOException", McaRegion.describe(new java.io.IOException()));
        assertTrue(McaRegion.describe(new java.io.IOException("磁盘已满")).endsWith("磁盘已满"));
        assertTrue(McaRegion.describe(new java.io.IOException("磁盘已满")).startsWith("IOException"));
        assertEquals("unknown", McaRegion.describe(null));
    }
}
