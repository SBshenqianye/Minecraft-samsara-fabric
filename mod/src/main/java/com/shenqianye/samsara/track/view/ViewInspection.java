package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.track.TrackEventStore;

import java.util.ArrayList;
import java.util.List;

/** 回看背包快照的只读分页展示。 */
final class ViewInspection {

    record Result(String text, int page, int pages) {
    }

    private ViewInspection() {
    }

    static Result inspect(ViewSession s, ViewTarget target, int page) {
        if (s.cursor == null || target == null || target.cursor == null || target.events == null) {
            return new Result("回看仍在加载或该段没有事件数据", 0, 0);
        }
        try {
            long time = s.trackTime(System.currentTimeMillis());
            TrackEventStore.InventorySnapshot snapshot;
            try {
                snapshot = target.events.inventoryAt(time);
            } catch (RuntimeException e) {
                Samsara.LOGGER.warn("[回看] 背包快照读取失败 viewer={} target={}: {}",
                        s.viewerName, target.name, e.getMessage());
                return new Result("背包快照读取失败（事件可能仍在写入）：" + e.getMessage(), 0, 0);
            }
            if (snapshot == null) {
                return new Result("该时刻没有完整背包快照（track.recordInventory=false，或该段录制于开启之前）；"
                        + "已记录的可见装备仍显示在人偶身上", 0, 0);
            }
            List<TrackEventStore.InventoryItem> items = new ArrayList<>(snapshot.items());
            items.sort(java.util.Comparator.comparingInt(TrackEventStore.InventoryItem::slot));
            int pageSize = 10;
            int pages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
            int current = Math.max(1, Math.min(pages, page));
            StringBuilder sb = new StringBuilder("[回看] ").append(target.name)
                    .append(" 的只读背包快照（").append(ViewTime.format(time)).append("）")
                    .append(" · 第 ").append(current).append("/").append(pages).append(" 页")
                    .append(" · 共 ").append(items.size()).append(" 件（只读，不会生成物品）");
            int from = (current - 1) * pageSize;
            int to = Math.min(items.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                TrackEventStore.InventoryItem item = items.get(i);
                sb.append("\n  槽 ").append(item.slot()).append(": ")
                        .append(item.stack().getItemName().getString())
                        .append(" ×").append(item.stack().getCount());
            }
            if (items.isEmpty()) {
                sb.append("\n  （空背包）");
            }
            if (current < pages) {
                sb.append("\n下一页：/samsara track view inspect ")
                        .append(s.viewerName).append(" ").append(current + 1);
            }
            return new Result(sb.toString(), current, pages);
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[回看] 背包快照读取失败 viewer={} target={}: {}",
                    s.viewerName, target.name, e.toString(), e);
            return new Result("背包快照读取失败（事件可能仍在写入）：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), 0, 0);
        }
    }
}
