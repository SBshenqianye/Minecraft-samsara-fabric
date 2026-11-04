package com.shenqianye.samsara.web;

import com.shenqianye.samsara.lottery.ItemValueService;
import com.shenqianye.samsara.lottery.LotteryService;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 死人乐透 Web 适配层。
 *
 * <p>管理端返回完整状态（实时死亡数、票、基金、历史）；用户端只返回奖池、
 * 票数、开奖时间与脱敏后的上期结果，不返回实时死亡数/UUID/路径。
 */
public final class LotteryWebService {
    private final MinecraftServer server;

    public LotteryWebService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> adminModel() {
        Map<String, Object> model = new java.util.LinkedHashMap<>(
                LotteryService.getInstance().adminModel());
        model.put("insurance",
                com.shenqianye.samsara.insurance.InsuranceService.getInstance().model());
        scrubFundIcons(model);
        return model;
    }

    /** 只把实际打包的图标发给前端，避免价值表/基金出现大量 404。 */
    @SuppressWarnings("unchecked")
    private static void scrubFundIcons(Map<String, Object> model) {
        Object fund = model.get("fund");
        if (!(fund instanceof Map<?, ?> fundMap)) return;
        Object items = ((Map<String, Object>) fundMap).get("items");
        if (!(items instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = (Map<String, Object>) raw;
            Object icon = row.get("icon");
            row.put("icon", icon == null ? "" : WebIcons.orEmpty(String.valueOf(icon)));
        }
    }

    public Map<String, Object> publicModel() {
        return LotteryService.getInstance().publicModel();
    }

    public Map<String, Object> values(String query, String category, String sort,
                                      int offset, int limit) {
        return run(() -> {
            Map<String, Object> out = ItemValueService.getInstance()
                    .queryItems(query, category, sort, offset, limit);
            if (out.get("values") instanceof List<?> rows) {
                for (Object entry : rows) {
                    if (entry instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) raw;
                        Object icon = row.get("icon");
                        row.put("icon", icon == null ? "" : WebIcons.orEmpty(String.valueOf(icon)));
                    }
                }
            }
            return out;
        });
    }

    public Map<String, Object> draw() {
        return run(() -> Map.of("ok", true, "message",
                LotteryService.getInstance().drawNow()));
    }

    public Map<String, Object> cancel() {
        return run(() -> Map.of("ok", true, "message",
                LotteryService.getInstance().cancelNow()));
    }

    private Map<String, Object> run(Supplier<Map<String, Object>> action) {
        if (server == null) {
            return Map.of("ok", false, "error", "服务器不可用");
        }
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        server.execute(() -> {
            try {
                result.set(action.get());
            } catch (RuntimeException e) {
                result.set(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return Map.of("ok", false, "error", "服务器线程超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "请求被中断");
        }
        return result.get() == null ? Map.of("ok", false, "error", "无结果") : result.get();
    }
}
