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
}
