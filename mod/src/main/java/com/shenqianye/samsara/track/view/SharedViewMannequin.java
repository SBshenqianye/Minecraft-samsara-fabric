package com.shenqianye.samsara.track.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同目标去重注册表（F-99）。
 *
 * <p>键 = {@code (目标 UUID, 周目, 维度)}。多个观看者回看同一目标、轨迹时间相差不超过
 * {@code view.dedupeWindowMs} 时，后加入者借用已有实体；自己的进度条/控制仍独立。
 * 主会话停止时按开始时间提升最早借用者；无人借用才丢弃实体。
 *
 * <p>同一键允许登记多个人偶：当借用者时间偏离后分裂出自己的实体时，
 * 原宿主仍留在表里，后续会话可继续选择时间相近的宿主，而不是互相覆盖。
 */
final class SharedViewMannequin {
    record Key(UUID uuid, int cycle, String dimension) {
    }

    static final class Host {
        final ViewSession session;
        final ViewTarget target;
        final ViewMannequin entity;
        final Key key;
        final long registeredAtMs;
        final List<ViewTarget> borrowers = new ArrayList<>();

        Host(ViewSession session, ViewTarget target, ViewMannequin entity, Key key) {
            this.session = session;
            this.target = target;
            this.entity = entity;
            this.key = key;
            this.registeredAtMs = System.currentTimeMillis();
        }
    }

    private static final Map<Key, List<Host>> HOSTS = new HashMap<>();

    private SharedViewMannequin() {
    }

    /**
     * 查找可借用的人偶。条件：同键、宿主仍在进行、实体未移除、
     * 两台会话当前轨迹时间差不超过窗口。多个宿主时选最早登记的。
     */
    static synchronized Host find(ViewSession requester, ViewTarget target, String dimension,
                                  long requesterTrackMs, long nowMs, long windowMs) {
        cleanupStale();
        if (requester == null || target == null || dimension == null) {
            return null;
        }
        List<Host> hosts = HOSTS.get(new Key(target.uuid, target.cycleId, dimension));
        if (hosts == null) {
            return null;
        }
        for (Host host : hosts) {
            if (host.session == requester || host.session.closed()) {
                continue;
            }
            if (host.entity == null || host.entity.isRemoved()
                    || host.target.mannequin != host.entity) {
                continue;
            }
            long hostTrack = host.session.trackTime(nowMs);
            if (Math.abs(hostTrack - requesterTrackMs) <= Math.max(0, windowMs)) {
                return host;
            }
        }
        return null;
    }

    static synchronized void register(ViewSession session, ViewTarget target, String dimension) {
        if (session == null || target == null || target.mannequin == null || dimension == null) {
            return;
        }
        removeHostForTarget(target);
        Key key = new Key(target.uuid, target.cycleId, dimension);
        HOSTS.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Host(session, target, target.mannequin, key));
        target.sharedHost = null;
        target.ownsMannequin = true;
    }

    static synchronized void borrow(ViewTarget target, Host host) {
        if (target == null || host == null) {
            return;
        }
        target.sharedHost = host;
        target.ownsMannequin = false;
        if (!host.borrowers.contains(target)) {
            host.borrowers.add(target);
        }
    }

    static synchronized void unborrow(ViewTarget target) {
        if (target == null || target.sharedHost == null) {
            return;
        }
        Host host = target.sharedHost;
        host.borrowers.remove(target);
        target.sharedHost = null;
    }

    /**
     * 释放目标自己持有的人偶。
     *
     * @return true = 已提升给其他会话借用，实体必须保留；false = 实体可以丢弃
     */
    static synchronized boolean release(ViewTarget target) {
        if (target == null) {
            return false;
        }
        unborrow(target);
        Host host = removeHostForTarget(target);
        if (host == null) {
            return false;
        }
        ViewTarget promoted = null;
        for (ViewTarget borrower : host.borrowers) {
            if (borrower == target || borrower.session == null || borrower.session.closed()) {
                continue;
            }
            if (borrower.mannequin != host.entity) {
                continue;
            }
            if (promoted == null || borrower.session.startWallMs < promoted.session.startWallMs) {
                promoted = borrower;
            }
        }
        if (promoted == null) {
            return false;
        }
        Host next = new Host(promoted.session, promoted, host.entity, host.key);
        next.borrowers.addAll(host.borrowers);
        next.borrowers.remove(promoted);
        for (ViewTarget borrower : next.borrowers) {
            borrower.sharedHost = next;
        }
        promoted.sharedHost = null;
        promoted.ownsMannequin = true;
        promoted.mannequin = host.entity;
        HOSTS.computeIfAbsent(host.key, ignored -> new ArrayList<>()).add(next);
        return true;
    }

    /** 宿主目标换维度/重新生成实体后更新键。 */
    static synchronized void updateKey(ViewSession session, ViewTarget target, String dimension) {
        if (target == null || target.mannequin == null || !target.ownsMannequin || dimension == null) {
            return;
        }
        Key expected = new Key(target.uuid, target.cycleId, dimension);
        Key oldKey = null;
        for (Map.Entry<Key, List<Host>> entry : HOSTS.entrySet()) {
            for (Host host : entry.getValue()) {
                if (host.target == target) {
                    if (entry.getKey().equals(expected)) {
                        return;
                    }
                    oldKey = entry.getKey();
                    break;
                }
            }
            if (oldKey != null) {
                break;
            }
        }
        if (oldKey != null) {
            removeHostForTarget(target);
            HOSTS.computeIfAbsent(expected, ignored -> new ArrayList<>())
                    .add(new Host(session, target, target.mannequin, expected));
        } else {
            register(session, target, dimension);
        }
    }

    static synchronized void clear() {
        HOSTS.clear();
    }

    private static Host removeHostForTarget(ViewTarget target) {
        Iterator<Map.Entry<Key, List<Host>>> entries = HOSTS.entrySet().iterator();
        Host removed = null;
        while (entries.hasNext()) {
            Map.Entry<Key, List<Host>> entry = entries.next();
            Iterator<Host> it = entry.getValue().iterator();
            while (it.hasNext()) {
                Host host = it.next();
                if (host.target == target) {
                    removed = host;
                    it.remove();
                }
            }
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
        return removed;
    }

    private static void cleanupStale() {
        Iterator<Map.Entry<Key, List<Host>>> entries = HOSTS.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, List<Host>> entry = entries.next();
            entry.getValue().removeIf(host -> host.entity == null || host.entity.isRemoved()
                    || host.session == null || host.session.closed()
                    || host.target.mannequin != host.entity);
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
    }
}
