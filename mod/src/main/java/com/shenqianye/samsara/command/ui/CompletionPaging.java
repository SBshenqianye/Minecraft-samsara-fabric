package com.shenqianye.samsara.command.ui;

import java.util.List;

/**
 * 候选分页的纯函数层（不依赖游戏状态，可直接单测）。
 *
 * <p>翻页只改变“当前展示哪一页”，不改变聊天输入、不执行命令、不产生世界副作用。
 * 页码必须钳制到有效范围：越界请求（旧按钮、手改 NBT）只展示第一页/最后一页。
 *
 * <p>上限：单次总候选 {@link #MAX_TOTAL} 项、{@link #MAX_PAGES} 页。超出的候选不展示，
 * 提示玩家继续输入缩小范围——这样既避免刷屏，也避免有人用翻页按钮反复请求大列表。
 */
public final class CompletionPaging {
    /** 最多展示的总候选数 */
    public static final int MAX_TOTAL = 200;
    /** 最多页数 */
    public static final int MAX_PAGES = 5;
    /** 同一玩家翻页节流（毫秒） */
    public static final long THROTTLE_MS = 250L;

    private CompletionPaging() {
    }

    /**
     * 一页候选。
     *
     * @param items     当前页的候选
     * @param page      实际页码（1 起）
     * @param pageCount 总页数（至少 1）
     * @param total     参与分页的候选总数（已按上限截断）
     * @param hasPrev   是否有上一页
     * @param hasNext   是否有下一页
     */
    public record Page<T>(List<T> items, int page, int pageCount, int total,
                          boolean hasPrev, boolean hasNext) {
    }

    /** 把全部候选按 {@code pageSize} 切页，并钳制页码与总数。 */
    public static <T> Page<T> slice(List<T> all, int requestedPage, int pageSize) {
        int size = Math.max(1, pageSize);
        int rawTotal = all == null ? 0 : all.size();
        int total = Math.min(rawTotal, Math.min(MAX_TOTAL, size * MAX_PAGES));
        int pageCount = Math.max(1, (total + size - 1) / size);
        int page = clamp(requestedPage, pageCount);
        int from = Math.min(total, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<T> items = all == null || from >= to ? List.of() : List.copyOf(all.subList(from, to));
        return new Page<>(items, page, pageCount, total, page > 1, page < pageCount);
    }

    /** 页码钳制到 {@code [1, pageCount]}；非数字/0/负数都回到第一页。 */
    public static int clamp(int page, int pageCount) {
        int count = Math.max(1, pageCount);
        return Math.max(1, Math.min(count, page));
    }

    /** 页面标题：{@code 第 N/M 页 · 共 X 项}。 */
    public static String label(int page, int pageCount, int total) {
        int count = Math.max(1, pageCount);
        return "第 " + clamp(page, count) + "/" + count + " 页 · 共 " + Math.max(0, total) + " 项";
    }

    /**
     * 节流判定：距离上次操作不足 {@link #THROTTLE_MS} 时拒绝。
     * {@code lastMs <= 0} 表示从未翻过页。
     */
    public static boolean allowAt(long lastMs, long nowMs) {
        return lastMs <= 0L || nowMs - lastMs >= THROTTLE_MS;
    }
}
