package com.shenqianye.samsara.season;

/**
 * 重生落点判定（纯逻辑，便于单测）。
 *
 * <p>老栈层（stack_0 / stack_1 …）是独立维度；原版玩家如果没有可用的床/重生锚，
 * 会回退到全局主世界，于是出现“死在老世界、重生到顶层同坐标”的问题。
 * 这里只回答：这次重生是否需要按“死亡所在层”纠正。
 */
public final class RespawnRule {

    private RespawnRule() {
    }

    /**
     * @param deathDimension   死亡时所在维度（可为 null）
     * @param respawnDimension 原版实际选择的重生维度（可为 null = 没有重生点）
     * @return true = 需要回退到死亡所在层的世界出生点
     */
    public static boolean needsFallback(String deathDimension, String respawnDimension) {
        if (deathDimension == null || deathDimension.isBlank()) {
            return false;
        }
        if (respawnDimension == null || respawnDimension.isBlank()) {
            return true;
        }
        return !deathDimension.equals(respawnDimension);
    }
}
