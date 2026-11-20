package com.shenqianye.samsara.lottery;

import java.util.List;
import java.util.Map;

/**
 * 乐透的 Web/命令共用视图（record 即 JSON 契约）。
 * <p>
 * 物品行仍是 {@code ItemStackModel} 的动态 Map（下一片再类型化），其余字段全部固定。
 * 字段名与顺序必须与旧 {@code LinkedHashMap} 输出一致；为保持"缺省键不出现"，
 * 可缺省字段用包装类型（null 会被 Gson 省略）。
 */
public final class LotteryViews {

    private LotteryViews() {
    }

    /** 下注行：day.bets[] */
    public record Bet(String name, int guess, int tickets, double value) {
    }

    /** 单个结算日视图（管理端）。 */
    public record Day(String day, String state, boolean covered, int deathCount, int playerDeaths,
                      int villagerDeaths, int namedMobDeaths, int tickets, boolean seeded,
                      double seedValue, List<Map<String, Object>> seedItems, double ownPot,
                      double pot, List<Map<String, Object>> potItems, String drawTime,
                      String cutoff, List<Bet> bets) {
    }

    /** 结算结果（管理端历史）。 */
    public record Result(String day, int deathCount, int winnerCount, double pot, double rake,
                         boolean rollover, boolean refunded, String refundReason,
                         List<String> winners, long at) {
    }

    /** 结算结果（用户端，中奖者做脱敏）。 */
    public record PublicResult(String day, int deathCount, int winnerCount, double pot,
                               boolean rollover, boolean refunded, List<String> winners) {
    }

    /** 玩家状态。 */
    public record Status(String bettingDay, String countingDay, String drawTime, String cutoff,
                         double pot, int tickets, int players, String myTickets, Result last,
                         boolean enabled) {
    }

    /** 用户端：正在下注的一轮。 */
    public record Round(String day, String drawTime, String cutoff, double ownPot, double seedValue,
                        double pot, int tickets, int players, List<Map<String, Object>> items) {
    }

    /** 用户端：正在累积的一轮。 */
    public record Active(String day, String drawTime, String cutoff, double seedValue, double pot,
                         int tickets, int players, List<Map<String, Object>> items) {
    }

    /** 管理端金价与金价联动。 */
    public record Gold(boolean enabled, double price, long updatedAt, String source,
                       double baseBlockValue, double reference, double blockValue,
                       double ingotValue, double nuggetValue) {
    }

    /** 用户端金价（只给公开字段）。 */
    public record PublicGold(boolean enabled, double price, long updatedAt, double blockValue) {
    }

    /** 基金（管理端）。 */
    public record Fund(double value, List<Map<String, Object>> items) {
    }

    /** 基金（用户端，只给公开物品字段）。 */
    public record PublicFund(double value, int count, List<Map<String, Object>> items) {
    }

    /** 基金仓库格子视图。 */
    public record FundInventory(boolean ok, int size, int minSize, List<String> slots,
                                List<Map<String, Object>> items, double value) implements Outcome {
    }

    /** 写操作结果：{@code {"ok":true,"message":"..."}}。 */
    public record Message(boolean ok, String message) implements Outcome {
    }

    /** 通用失败：{@code {"ok":false,"error":"..."}}。 */
    public record Error(boolean ok, String error) implements Outcome {
    }

    /** 带 HTTP 语义的写入失败：{@code {"ok":false,"status":400,"error":"..."}}。 */
    public record WriteError(boolean ok, int status, String error) implements Outcome {
        @Override
        public int httpStatus() {
            return status;
        }
    }

    /** 乐透写接口/仓库接口的统一出口；JSON 只序列化 record 组件。 */
    public sealed interface Outcome permits FundInventory, Message, Error, WriteError {
        boolean ok();

        default int httpStatus() {
            return ok() ? 200 : 400;
        }
    }

    /** 待领取实物。 */
    public record Claim(String owner, String dataUuid, String reason, long at,
                        List<Map<String, Object>> items) {
    }

    /** `/api/user/lottery`：公开模型 + 保险摘要（服务端自己不填 insurance）。 */
    public record PublicModel(Boolean ok, String scope, Boolean enabled, Round round, Active active,
                              Double rollover, PublicResult last, PublicFund fund, PublicGold gold,
                              Map<String, Object> insurance) {

        public PublicModel withInsurance(Map<String, Object> summary) {
            return new PublicModel(ok, scope, enabled, round, active, rollover, last, fund, gold,
                    summary);
        }
    }

    /** `/api/lottery`：管理端完整模型。 */
    public record Admin(boolean enabled, String mode, Day bettingDay, Day countingDay,
                        double rollover, List<Map<String, Object>> rolloverItems,
                        double activePot, List<Map<String, Object>> activePotItems,
                        double bettingPot, List<Map<String, Object>> bettingPotItems,
                        List<Claim> claims, Gold gold, Fund fund, List<Result> history,
                        String configPath, Map<String, Object> insurance) {

        public Admin withInsurance(Map<String, Object> summary) {
            return new Admin(enabled, mode, bettingDay, countingDay, rollover, rolloverItems,
                    activePot, activePotItems, bettingPot, bettingPotItems, claims, gold, fund,
                    history, configPath, summary);
        }
    }
}
