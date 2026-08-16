package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 档3 方块变化记录器（DiffRecorder）。
 * <p>
 * 由 {@link com.shenqianye.samsara.mixin.ServerLevelMixin} 在 {@code ServerLevel.setBlock}
 * 处调用：当方块<b>类型</b>实际变化（如破坏→空气、放置、TNT 爆破）时，把该格「变化前的状态」
 * 记入当前记录窗口的 DiffStore，供倒流时逐格恢复。
 * <p>
 * <b>记录窗口（设计规范 §11 快照语义，2026-08-17 修订）</b>：记录<b>常开</b>——服务器启动即
 * 写入 {@code diff/current/}；倒流真正启动时（扫描成功后）把 current <b>冻结</b> 为
 * {@code diff/s<赛季id>/}（=本次倒流的恢复源，窗口 = 上次删块完成之后到本次倒流开始），
 * 并开启新的 current；倒流完成（物理删除执行）时<b>重置</b>窗口（丢弃倒流期间的改动，
 * 它们属于倒流开始之前的世界状态）。中止倒流不重置（窗口继续累计）。
 * <p>
 * 过滤：仅记录 block 类型变化（before.block != after.block）——自动过滤红石刻状态/流体/作物等
 * 只换状态不变类型的"世界模拟噪声"（与设计文档 §12.3 一致）。
 */
public class DiffRecorder {
    private static DiffStore store;
    private static boolean enabled;
    private static Path configDir;

    private DiffRecorder() {
    }

    /** 服务器启动时初始化：开启常开记录窗口（diff/current） */
    public static void init(Path samsaraConfigDir) {
        configDir = samsaraConfigDir;
        store = DiffStore.open(configDir.resolve("diff").resolve("current"));
        enabled = true;
        Samsara.LOGGER.info("[周目管理 samsara] 档3 差异记录已开启（常开窗口 diff/current）");
    }

    /**
     * 冻结当前记录窗口为 {@code diff/s<seasonId>/} 并开启新窗口（倒流启动时调用）。
     * 冻结后的 s<seasonId> 即本次倒流的恢复源。失败（如目录已被占用）时保留 current 并告警。
     */
    public static synchronized void freezeCurrent(int seasonId) {
        if (configDir == null || store == null) {
            return;
        }
        try {
            store.flush(); // 先落盘，再移动目录
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 冻结前 flush 失败: {}", e.getMessage());
        }
        Path current = configDir.resolve("diff").resolve("current");
        Path dst = configDir.resolve("diff").resolve("s" + seasonId);
        try {
            if (Files.exists(current)) {
                if (Files.exists(dst)) {
                    Samsara.LOGGER.warn("[周目管理 samsara] 冻结覆盖已有恢复库 {}（旧数据作废）", dst);
                    deleteRecursively(dst);
                }
                Files.createDirectories(current.getParent());
                Files.move(current, dst);
            }
            Samsara.LOGGER.info("[周目管理 samsara] 差异库冻结: diff/current → diff/s{}（本次倒流恢复源，窗口=上次删块后至今）", seasonId);
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] 差异库冻结失败（保留 current 继续记录）: {}", e.getMessage());
        }
        store = DiffStore.open(current);
    }

    /**
     * 重置记录窗口（倒流完成、物理删除执行时调用）：丢弃倒流期间的改动，
     * 开启新窗口（下次倒流的恢复源将从此刻起累计）。
     */
    public static synchronized void resetWindow() {
        if (configDir == null) {
            return;
        }
        Path current = configDir.resolve("diff").resolve("current");
        try {
            store.flush();
            deleteRecursively(current);
            Files.createDirectories(current);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 重置记录窗口失败: {}", e.getMessage());
        }
        store = DiffStore.open(current);
        enabled = true;
        Samsara.LOGGER.info("[周目管理 samsara] 差异记录窗口已重置（丢弃倒流期间改动，重新累计）");
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path f : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(f);
            }
        }
    }

    /** 临时开关记录（倒流恢复自身 setBlock 时暂停，避免"恢复→被记录→再被恢复成空气"自噬） */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean recording() {
        return enabled;
    }

    /** 当前记录窗口的库 */
    public static DiffStore store() {
        return store;
    }

    /**
     * fake 注入目标库：倒流进行中注入到<b>恢复源</b>（diff/s&lt;N&gt;，倒流可见）；
     * 无倒流时注入当前记录窗口（current，下次倒流可见）。
     */
    public static DiffStore fakeTarget() {
        RewindEngine re = RewindEngine.getInstance();
        if (re.running() && re.currentSeasonId() > 0) {
            return DiffStore.forSeason(configDir, re.currentSeasonId());
        }
        return store;
    }

    /**
     * 由 mixin 调用的记录入口。
     * 若开启且 block 类型真实变化，记录 before 状态。
     */
    public static void record(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState before,
                              net.minecraft.world.level.block.state.BlockState after) {
        if (!enabled || store == null) {
            return;
        }
        if (before.getBlock() == after.getBlock()) {
            return; // 仅类型变化才记（过滤红石/流体/作物等）
        }
        // 忽略世界生成/越界
        if (!level.hasChunkAt(pos)) {
            return;
        }
        int stateId = net.minecraft.world.level.block.Block.getId(before);
        if (stateId < 0) {
            return;
        }
        store.record(pos.getX(), pos.getY(), pos.getZ(), stateId, System.currentTimeMillis());
    }

    /** 石头方块的状态 id（动态获取，26.2 注册表顺序不能硬编码 1） */
    public static int stoneStateId() {
        return net.minecraft.world.level.block.Block.getId(
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
    }

    /**
     * 注入虚假 diff 记录（测试可视化倒流用，不需真的改方块）。
     * 在指定区块随机位置写 count 条记录，stateId = 指定方块（恢复时会把这些格还原成该方块，
     * 若原本是空气则"变出方块"，肉眼可见倒流恢复）。
     * 时间戳=now（视为最新改动 → 倒流最先恢复）。
     *
     * @return 描述文本
     */
    public static String fakeDiff(int cx, int cz, int count, int stateId) {
        DiffStore target = fakeTarget();
        if (target == null) {
            return "档3 差异库未初始化（先 /season start 或 /samsara debug flow setup + flow rewind）";
        }
        if (count <= 0 || count > 4096) {
            return "数量须在 1~4096 之间";
        }
        java.util.Random rnd = new java.util.Random();
        long now = System.currentTimeMillis();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int i = 0; i < count; i++) {
            int x = baseX + rnd.nextInt(16);
            int z = baseZ + rnd.nextInt(16);
            int y = 64 + rnd.nextInt(96); // 64~159，地表附近便于观察
            target.record(x, y, z, stateId, now - i); // 时间递减：i=0 最新
        }
        target.flush(); // 批量落盘，确保立即可读
        return "已注入 " + count + " 条虚假 diff 到区块 (" + cx + "," + cz + ")（stateId=" + stateId
                + "，y=64~159 随机）· " + (RewindEngine.getInstance().running()
                ? "倒流恢复源" : "记录窗口（下次倒流）") + " 生效";
    }

    /**
     * 空中"字画"：用点阵字体把 text 逐像素写成虚假 diff（每个像素 = 一条记录，stateId=指定方块）。
     * 倒流时这些格逐格"凭空浮现"，形成空中文字/图案（可视化倒流测试）。
     * <p>
     * 若传入 level（命令层），注入的同时<b>立即放置方块</b>——字画当场可见；
     * diff 记录仍保留，倒流恢复时目标已是该方块则跳过（字画保持）。
     *
     * @param x0/y0/z0 文字左上角起始坐标（y 建议 100+，抬头可见）
     * @param text     内容（A-Z/0-9/部分符号；小写自动转大写）
     * @param stateId  方块状态 id（默认 1=石头）
     * @param scale    放大倍数（1=5x5，2=10x10，…）
     * @return 描述文本
     */
    public static String fakeDiffText(int x0, int y0, int z0, String text, int stateId, int scale) {
        return fakeDiffText(null, x0, y0, z0, text, stateId, scale);
    }

    /** 同 {@link #fakeDiffText(int, int, int, String, int, int)}，但 level 非空时立即放置字画方块 */
    public static String fakeDiffText(ServerLevel level, int x0, int y0, int z0, String text,
                                      int stateId, int scale) {
        DiffStore target = fakeTarget();
        if (target == null) {
            return "档3 差异库未初始化（先 /samsara debug flow setup + flow rewind）";
        }
        if (scale < 1 || scale > 8) {
            return "scale 须在 1~8";
        }
        if (text == null || text.isEmpty()) {
            return "文字为空";
        }
        // 总量上限：防止超大字画（长文本 × scale²）一次注入数万条阻塞服务器主线程
        int maxPixels = 50_000;
        long estimated = (long) text.length() * 25 * scale * scale;
        if (estimated > maxPixels) {
            return "字画规模过大（约 " + estimated + " 格 > 上限 " + maxPixels
                    + "）：请缩短文字或调小 scale（当前 " + text.length() + " 字符 × scale=" + scale + "）";
        }
        long now = System.currentTimeMillis();
        int idx = 0;
        int skipped = 0;
        StringBuilder skippedChars = new StringBuilder();
        int cursorX = 0; // 相对起点 x 偏移
        for (char ch : text.toUpperCase().toCharArray()) {
            int fi = ch - ' ';
            int[] glyph = (fi >= 0 && fi < FONT_5X7.length) ? FONT_5X7[fi] : null;
            if (glyph == null) {
                skipped++;
                if (skippedChars.length() < 12) {
                    skippedChars.append(ch);
                }
                continue; // 不支持的字符（含中文/emoji）跳过，避免数组越界崩溃
            }
            // 字库为行优先：每个元素 = 一行，低 5 位 = 5 列（bit0=最左列，行0=顶部）。
            // 之前按"列优先"读（元素当列、位当行）导致字形转置 90° 躺下，已修正。
            for (int row = 0; row < glyph.length; row++) {
                int bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << col)) != 0) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int x = x0 + (cursorX + col) * scale + sx;
                                int y = y0 - row * scale - sy; // 行0=顶，向下画（y 减小）
                                int z = z0;
                                target.record(x, y, z, stateId, now - idx);
                                if (level != null) {
                                    // 立即放置方块使字画当场可见；暂停记录避免"放置被记成新变化"。
                                    // 强制加载区块（getChunk 会加载/生成），保证即使远离玩家也能放置。
                                    boolean was = enabled;
                                    enabled = false;
                                    try {
                                        net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(x, y, z);
                                        level.getChunk(x >> 4, z >> 4);
                                        level.setBlock(bp, net.minecraft.world.level.block.Block.stateById(stateId), 3);
                                    } finally {
                                        enabled = was;
                                    }
                                }
                                idx++;
                            }
                        }
                    }
                }
            }
            cursorX += 6; // 字符间距：5 宽 + 1 空格
        }
        target.flush(); // 批量落盘，确保立即可读（倒流恢复读文件）
        if (idx == 0) {
            return "文字不含可绘制字符（仅支持 A-Z/0-9/部分符号）";
        }
        String skipNote = skipped > 0
                ? "（已跳过 " + skipped + " 个不支持字符「" + skippedChars + "」，仅支持 A-Z/0-9/部分符号）"
                : "";
        return "已注入 " + idx + " 条虚假 diff（空中字画 " + text.toUpperCase() + skipNote + "，stateId=" + stateId
                + "，scale=" + scale + "）· 起点 (" + x0 + "," + y0 + "," + z0 + ") · 倒流时逐格浮现";
    }

    /** 5x5 点阵字体（行优先）：下标 = char - ' '（32），每字符 5 行，每行低 5 位 = 5 列（bit0=最左列，行0=顶部） */
    private static final int[][] FONT_5X7 = new int[95][];
    static {
        // 空格
        FONT_5X7[' ' - ' '] = new int[]{0, 0, 0, 0, 0};
        // !  .  #  $  %  &  '  (  )  *  +  ,  -  .  /
        FONT_5X7['!' - ' '] = new int[]{0x04, 0x04, 0x04, 0x00, 0x04};
        FONT_5X7['"' - ' '] = new int[]{0x0A, 0x0A, 0x00, 0x00, 0x00};
        FONT_5X7['#' - ' '] = new int[]{0x0A, 0x1F, 0x0A, 0x1F, 0x0A};
        FONT_5X7['$' - ' '] = new int[]{0x04, 0x0E, 0x05, 0x0E, 0x04};
        FONT_5X7['%' - ' '] = new int[]{0x12, 0x08, 0x04, 0x02, 0x09};
        FONT_5X7['&' - ' '] = new int[]{0x06, 0x09, 0x06, 0x0A, 0x05};
        FONT_5X7['\'' - ' '] = new int[]{0x04, 0x04, 0x00, 0x00, 0x00};
        FONT_5X7['(' - ' '] = new int[]{0x08, 0x04, 0x04, 0x04, 0x08};
        FONT_5X7[')' - ' '] = new int[]{0x02, 0x04, 0x04, 0x04, 0x02};
        FONT_5X7['*' - ' '] = new int[]{0x04, 0x15, 0x0E, 0x15, 0x04};
        FONT_5X7['+' - ' '] = new int[]{0x00, 0x04, 0x0E, 0x04, 0x00};
        FONT_5X7[',' - ' '] = new int[]{0x00, 0x00, 0x00, 0x02, 0x02};
        FONT_5X7['-' - ' '] = new int[]{0x00, 0x00, 0x0E, 0x00, 0x00};
        FONT_5X7['.' - ' '] = new int[]{0x00, 0x00, 0x00, 0x00, 0x02};
        FONT_5X7['/' - ' '] = new int[]{0x10, 0x08, 0x04, 0x02, 0x01};
        // 0-9
        FONT_5X7['0' - ' '] = new int[]{0x0E, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['1' - ' '] = new int[]{0x04, 0x06, 0x04, 0x04, 0x0E};
        FONT_5X7['2' - ' '] = new int[]{0x0E, 0x10, 0x0E, 0x01, 0x1F};
        FONT_5X7['3' - ' '] = new int[]{0x1F, 0x08, 0x0E, 0x08, 0x0F}; // 简化
        FONT_5X7['4' - ' '] = new int[]{0x08, 0x0C, 0x0A, 0x1F, 0x08};
        FONT_5X7['5' - ' '] = new int[]{0x1F, 0x01, 0x0E, 0x10, 0x0E};
        FONT_5X7['6' - ' '] = new int[]{0x0E, 0x01, 0x0F, 0x11, 0x0E};
        FONT_5X7['7' - ' '] = new int[]{0x1F, 0x10, 0x08, 0x04, 0x04};
        FONT_5X7['8' - ' '] = new int[]{0x0E, 0x11, 0x0E, 0x11, 0x0E};
        FONT_5X7['9' - ' '] = new int[]{0x0E, 0x11, 0x1E, 0x10, 0x0E};
        // : ; < = > ? @
        FONT_5X7[':' - ' '] = new int[]{0x00, 0x02, 0x00, 0x02, 0x00};
        FONT_5X7[';' - ' '] = new int[]{0x00, 0x02, 0x00, 0x02, 0x02};
        FONT_5X7['<' - ' '] = new int[]{0x08, 0x04, 0x02, 0x04, 0x08};
        FONT_5X7['=' - ' '] = new int[]{0x00, 0x0E, 0x00, 0x0E, 0x00};
        FONT_5X7['>' - ' '] = new int[]{0x02, 0x04, 0x08, 0x04, 0x02};
        FONT_5X7['?' - ' '] = new int[]{0x0E, 0x10, 0x08, 0x00, 0x04};
        FONT_5X7['@' - ' '] = new int[]{0x0E, 0x11, 0x17, 0x01, 0x0E};
        // A-Z
        FONT_5X7['A' - ' '] = new int[]{0x0E, 0x11, 0x1F, 0x11, 0x11};
        FONT_5X7['B' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x11, 0x0F};
        FONT_5X7['C' - ' '] = new int[]{0x0E, 0x11, 0x01, 0x11, 0x0E};
        FONT_5X7['D' - ' '] = new int[]{0x0F, 0x11, 0x11, 0x11, 0x0F};
        FONT_5X7['E' - ' '] = new int[]{0x1F, 0x01, 0x0F, 0x01, 0x1F};
        FONT_5X7['F' - ' '] = new int[]{0x1F, 0x01, 0x0F, 0x01, 0x01};
        FONT_5X7['G' - ' '] = new int[]{0x0E, 0x11, 0x19, 0x11, 0x0E};
        FONT_5X7['H' - ' '] = new int[]{0x11, 0x11, 0x1F, 0x11, 0x11};
        FONT_5X7['I' - ' '] = new int[]{0x0E, 0x04, 0x04, 0x04, 0x0E};
        FONT_5X7['J' - ' '] = new int[]{0x1C, 0x08, 0x08, 0x09, 0x06};
        FONT_5X7['K' - ' '] = new int[]{0x11, 0x09, 0x07, 0x09, 0x11};
        FONT_5X7['L' - ' '] = new int[]{0x01, 0x01, 0x01, 0x01, 0x1F};
        FONT_5X7['M' - ' '] = new int[]{0x11, 0x1B, 0x15, 0x11, 0x11};
        FONT_5X7['N' - ' '] = new int[]{0x11, 0x13, 0x15, 0x19, 0x11};
        FONT_5X7['O' - ' '] = new int[]{0x0E, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['P' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x01, 0x01};
        FONT_5X7['Q' - ' '] = new int[]{0x0E, 0x11, 0x15, 0x09, 0x16};
        FONT_5X7['R' - ' '] = new int[]{0x0F, 0x11, 0x0F, 0x09, 0x11};
        FONT_5X7['S' - ' '] = new int[]{0x0E, 0x01, 0x0E, 0x10, 0x0E};
        FONT_5X7['T' - ' '] = new int[]{0x1F, 0x04, 0x04, 0x04, 0x04};
        FONT_5X7['U' - ' '] = new int[]{0x11, 0x11, 0x11, 0x11, 0x0E};
        FONT_5X7['V' - ' '] = new int[]{0x11, 0x11, 0x11, 0x0A, 0x04};
        FONT_5X7['W' - ' '] = new int[]{0x11, 0x11, 0x15, 0x1B, 0x11};
        FONT_5X7['X' - ' '] = new int[]{0x11, 0x0A, 0x04, 0x0A, 0x11};
        FONT_5X7['Y' - ' '] = new int[]{0x11, 0x0A, 0x04, 0x04, 0x04};
        FONT_5X7['Z' - ' '] = new int[]{0x1F, 0x08, 0x04, 0x02, 0x1F};
        // [ \ ] ^ _ `
        FONT_5X7['[' - ' '] = new int[]{0x0E, 0x02, 0x02, 0x02, 0x0E};
        FONT_5X7['\\' - ' '] = new int[]{0x01, 0x02, 0x04, 0x08, 0x10};
        FONT_5X7[']' - ' '] = new int[]{0x0E, 0x08, 0x08, 0x08, 0x0E};
        FONT_5X7['^' - ' '] = new int[]{0x04, 0x0A, 0x00, 0x00, 0x00};
        FONT_5X7['_' - ' '] = new int[]{0x00, 0x00, 0x00, 0x00, 0x1F};
        FONT_5X7['`' - ' '] = new int[]{0x02, 0x04, 0x00, 0x00, 0x00};
        // 其他常用
        FONT_5X7['~' - ' '] = new int[]{0x0A, 0x15, 0x00, 0x00, 0x00};
    }
}
