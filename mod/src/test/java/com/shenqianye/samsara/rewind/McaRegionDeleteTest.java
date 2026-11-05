package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05 修复回归：McaRegion.deleteChunks 的整文件删除判据必须是
 * "所有已占用 slot 都在待删列表"，禁止用"占用数 &lt;= 待删数"近似。
 * <p>
 * 现场复现：entities/r.0.0.mca 只有 2 个占用槽（Bota 待删 + BOTB 受保护），
 * 待删列表数百个区块 → 旧判据把整个实体文件删掉，受保护区块实体丢失。
 */
class McaRegionDeleteTest {
    @TempDir
    Path dir;

    private static final int HEADER = 8192;

    private static int slotOf(int cx, int cz) {
        return Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
    }

    /** 建一个最小 .mca：8KB header，指定占用槽各写 1 扇区假数据 */
    private Path mca(String subdir, String name, int... slots) throws Exception {
        Path f = dir.resolve(subdir).resolve(name);
        Files.createDirectories(f.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(HEADER + 4096L * (slots.length + 1));
            int sector = 2;
            for (int s : slots) {
                // MCA 位置槽：3 字节扇区偏移（big-endian）+ 1 字节扇区数
                raf.seek(s * 4L);
                raf.write(new byte[]{0, 0, (byte) sector, 1});
                raf.seek((long) HEADER / 2 + s * 4L);
                raf.writeInt(12345);
                raf.seek((long) sector * 4096);
                raf.writeInt(0x01020304);
                sector++;
            }
        }
        return f;
    }

    private static int offsetAt(Path f, int slot) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(slot * 4L);
            byte[] b = new byte[3];
            raf.readFully(b);
            return ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
        }
    }

    @Test
    void sparseEntityFileKeepsProtectedSlot() throws Exception {
        // (23,25) 在待删列表（(cx+cz)%2==0），(25,24) 受保护不在列表
        int markedSlot = slotOf(23, 25);
        int protectedSlot = slotOf(25, 24);
        Path f = mca("entities", "r.0.0.mca", markedSlot, protectedSlot);

        List<int[]> marked = new ArrayList<>();
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                if ((cx + cz) % 2 == 0) {
                    marked.add(new int[]{cx, cz});
                }
            }
        }

        int n = McaRegion.deleteChunks(dir, marked);

        assertTrue(Files.exists(f), "实体文件不得被整文件删除（旧判据会误删）");
        assertEquals(0, offsetAt(f, markedSlot), "待删区块的实体槽应清零");
        assertNotEquals(0, offsetAt(f, protectedSlot), "受保护区块的实体槽必须保留");
        assertEquals(1, n, "只应实际删除 1 个占用槽");
    }

    @Test
    void wholeFileDeletedOnlyWhenAllOccupiedMarked() throws Exception {
        int a = slotOf(10, 10);
        int b = slotOf(11, 11);
        Path f = mca("region", "r.0.0.mca", a, b);

        int n = McaRegion.deleteChunks(dir, List.of(new int[]{10, 10}, new int[]{11, 11}));

        assertFalse(Files.exists(f), "全部占用槽都在待删列表时应整文件删除");
        assertEquals(2, n);
    }

    @Test
    void mixedRegionFileZeroesOnlyMarked() throws Exception {
        int a = slotOf(10, 10);
        int b = slotOf(11, 11);
        Path f = mca("region", "r.0.0.mca", a, b);

        int n = McaRegion.deleteChunks(dir, List.of(new int[]{10, 10}));

        assertTrue(Files.exists(f));
        assertEquals(0, offsetAt(f, a), "待删 slot 应清零");
        assertNotEquals(0, offsetAt(f, b), "非待删 slot 必须保留");
        assertEquals(1, n);
    }
}
