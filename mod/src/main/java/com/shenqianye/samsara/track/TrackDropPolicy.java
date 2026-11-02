package com.shenqianye.samsara.track;

/**
 * 装备掉落开关的纯逻辑（F-107）。
 * <p>
 * 用户裁决：允许复制，默认开启。只有“捕捉后变成普通实体的人偶”在死亡时掉录制装备；
 * 回看人偶永远不掉落，回放停止/人偶 {@code discard()} 也不走死亡掉落路径。
 */
public final class TrackDropPolicy {
    private TrackDropPolicy() {
    }

    public static boolean shouldDropEquipment(boolean replayEntity, boolean captured,
                                              boolean viewSession, boolean allowed) {
        return replayEntity && captured && !viewSession && allowed;
    }
}
