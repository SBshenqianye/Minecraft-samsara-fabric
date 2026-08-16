package com.shenqianye.samsara.rewind;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;

/**
 * 档3 方块变化记录器（DiffRecorder）。
 * <p>
 * 由 {@link com.shenqianye.samsara.mixin.ServerLevelMixin} 在 {@code ServerLevel.setBlock}
 * 处调用：当方块<b>类型</b>实际变化（如破坏→空气、放置、TNT 爆破）时，把该格「变化前的状态」
 * 记入当前周目的 DiffStore，供倒流时逐格恢复。
 * <p>
 * 过滤：仅记录 block 类型变化（before.block != after.block）——自动过滤红石刻状态/流体/作物等
 * 只换状态不变类型的"世界模拟噪声"（与设计文档 §12.3 一致）。
 */
public class DiffRecorder {
    private static DiffStore store;
    private static int seasonId = -1;
    private static boolean enabled;

    private DiffRecorder() {
    }

    /** 开始记录新一轮目（start 时调用）；关闭则停止记 */
    public static void startRecording(int sid) {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        store = DiffStore.forSeason(configDir, sid);
        seasonId = sid;
        enabled = true;
        com.shenqianye.samsara.Samsara.LOGGER.info("[周目管理 samsara] 档3 差异记录开始（周目 #{}）", sid);
    }

    /** 停止记录（end / abort 时调用），保留已写 diff 库 */
    public static void stopRecording() {
        enabled = false;
    }

    /** 临时开关记录（倒流恢复自身 setBlock 时暂停，避免"恢复→被记录→再被恢复成空气"自噬） */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean recording() {
        return enabled;
    }

    public static int seasonId() {
        return seasonId;
    }

    public static DiffStore store() {
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
        if (store == null) {
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
            store.record(x, y, z, stateId, now - i); // 时间递减：i=0 最新
        }
        return "已注入 " + count + " 条虚假 diff 到区块 (" + cx + "," + cz + ")（stateId=" + stateId
                + "，y=64~159 随机）· 倒流时会把这些格还原为指定方块";
    }

    /**
     * 空中"字画"：用点阵字体把 text 逐像素写成虚假 diff（每个像素 = 一条记录，stateId=指定方块）。
     * 倒流时这些格逐格"凭空浮现"，形成空中文字/图案（可视化倒流测试）。
     *
     * @param x0/y0/z0 文字左上角起始坐标（y 建议 100+，抬头可见）
     * @param text     内容（A-Z/0-9/部分符号；小写自动转大写）
     * @param stateId  方块状态 id（默认 1=石头）
     * @param scale    放大倍数（1=5x7，2=10x14，…）
     * @return 描述文本
     */
    public static String fakeDiffText(int x0, int y0, int z0, String text, int stateId, int scale) {
        if (store == null) {
            return "档3 差异库未初始化（先 /samsara debug flow setup + flow rewind）";
        }
        if (scale < 1 || scale > 8) {
            return "scale 须在 1~8";
        }
        if (text == null || text.isEmpty()) {
            return "文字为空";
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
            for (int col = 0; col < 5; col++) {
                int bits = glyph[col];
                for (int row = 0; row < 7; row++) {
                    if ((bits & (1 << row)) != 0) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int x = x0 + (cursorX + col) * scale + sx;
                                int y = y0 - row * scale - sy; // 向上画（y 减小）
                                int z = z0;
                                store.record(x, y, z, stateId, now - idx);
                                idx++;
                            }
                        }
                    }
                }
            }
            cursorX += 6; // 字符间距：5 宽 + 1 空格
        }
        if (idx == 0) {
            return "文字不含可绘制字符（仅支持 A-Z/0-9/部分符号）";
        }
        String skipNote = skipped > 0
                ? "（已跳过 " + skipped + " 个不支持字符「" + skippedChars + "」，仅支持 A-Z/0-9/部分符号）"
                : "";
        return "已注入 " + idx + " 条虚假 diff（空中字画 " + text.toUpperCase() + skipNote + "，stateId=" + stateId
                + "，scale=" + scale + "）· 起点 (" + x0 + "," + y0 + "," + z0 + ") · 倒流时逐格浮现";
    }

    /** 5x7 点阵字体：下标 = char - ' '（32），每字符 5 列，每列低 7 位 = 7 行（bit0=最底行） */
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
