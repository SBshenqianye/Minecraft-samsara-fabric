package com.shenqianye.samsara.track.view;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 回看人偶的可见观看者集合。
 * <p>
 * 回看是“个人摄像头”：{@link ViewMannequin} 只向正在看这个实体的观看者发包，
 * 其他玩家连实体包都收不到（不会看到走动的假玩家、皮肤、动作）。
 * <p>
 * 空集合 = 对所有人隐藏：人偶还没归属任何会话（或在提升/清理的中间态）时，
 * 宁可短暂不可见，也不要漏给外人。
 */
final class ViewAudience {
    private final Set<UUID> viewers = new HashSet<>();

    /** 允许该观看者收到人偶实体包（幂等，null 忽略）。 */
    void allow(UUID viewer) {
        if (viewer != null) {
            viewers.add(viewer);
        }
    }

    /** 取消该观看者的可见性（幂等，null 忽略）。 */
    void deny(UUID viewer) {
        if (viewer != null) {
            viewers.remove(viewer);
        }
    }

    boolean allows(UUID viewer) {
        return viewer != null && viewers.contains(viewer);
    }

    int size() {
        return viewers.size();
    }

    boolean isEmpty() {
        return viewers.isEmpty();
    }

    void clear() {
        viewers.clear();
    }
}
