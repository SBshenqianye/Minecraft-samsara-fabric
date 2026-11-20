package com.shenqianye.samsara.web;

import com.shenqianye.samsara.insurance.InsuranceService;
import com.shenqianye.samsara.lottery.ItemValueService;
import com.shenqianye.samsara.lottery.LotteryService;
import com.shenqianye.samsara.lottery.LotteryViews;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 乐透透明 Web 服务层。
 *
 * <p>这里不复制业务规则，只做线程切换与展示层修饰：票面数据在服务器线程生成，
 * 用户端只回结果；所有响应都是 {@link LotteryViews} 里的 record。
 */
public final class LotteryWebService {
    private final MinecraftServer server;

    public LotteryWebService(MinecraftServer server) {
        this.server = server;
    }

    public LotteryViews.Admin adminModel() {
        LotteryViews.Admin admin = LotteryService.getInstance().adminModel();
        scrubFundIcons(admin.fund());
        return admin.withInsurance(InsuranceService.getInstance().model());
    }

    /** 只把实际存在的图标发给前端；缺失值的路径/名称在执行阶段会返回 404。 */
    private static void scrubFundIcons(LotteryViews.Fund fund) {
        for (Map<String, Object> row : fund.items()) {
            Object icon = row.get("icon");
            row.put("icon", icon == null ? "" : WebIcons.orEmpty(String.valueOf(icon)));
        }
    }

    public LotteryViews.PublicModel publicModel() {
        return LotteryService.getInstance().publicModel();
    }

    public Map<String, Object> values(String query, String category, String sort,
                                      int offset, int limit) {
        return runItemValues(() -> {
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

    public LotteryViews.Outcome draw() {
        return run(() -> new LotteryViews.Message(true,
                LotteryService.getInstance().drawNow()));
    }

    public LotteryViews.Outcome cancel() {
        return run(() -> new LotteryViews.Message(true,
                LotteryService.getInstance().cancelNow()));
    }

    public LotteryViews.Outcome fundInventory() {
        return run(() -> LotteryService.getInstance().fundInventory());
    }

    public LotteryViews.Outcome writeFundInventory(String body) {
        List<String> slots = parseSlots(body);
        if (slots == null) {
            return new LotteryViews.WriteError(false, 400, "请求体应包含 slots 字符串数组");
        }
        return run(() -> LotteryService.getInstance().writeFundInventory(slots));
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseSlots(String body) {
        try {
            Map<String, Object> request = new com.google.gson.Gson().fromJson(body, Map.class);
            if (request == null || !(request.get("slots") instanceof List<?> list)) return null;
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(item == null ? "" : String.valueOf(item));
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 在服务器线程执行写操作；失败返回 {@code ok=false} 的错误体。 */
    private LotteryViews.Outcome run(Supplier<LotteryViews.Outcome> action) {
        if (server == null) {
            return new LotteryViews.Error(false, "服务器不可用");
        }
        AtomicReference<LotteryViews.Outcome> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        server.execute(() -> {
            try {
                result.set(action.get());
            } catch (RuntimeException e) {
                result.set(new LotteryViews.Error(false, String.valueOf(e.getMessage())));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return new LotteryViews.Error(false, "服务器线程超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LotteryViews.Error(false, "请求被中断");
        }
        return result.get() == null ? new LotteryViews.Error(false, "无结果") : result.get();
    }

    /** 价值表是动态物品域，暂不在乐透视图内做类型化。 */
    private Map<String, Object> runItemValues(Supplier<Map<String, Object>> action) {
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
