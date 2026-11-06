package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.insurance.InsuranceService;
import com.shenqianye.samsara.lottery.GoldPriceService;
import com.shenqianye.samsara.lottery.ItemValueService;
import com.shenqianye.samsara.lottery.LotteryConfig;
import com.shenqianye.samsara.lottery.LotteryService;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.permission.PermissionRequirements;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 死人乐透命令：{@code /samsara lottery ...}。 */
public final class LotteryCommand {
    private LotteryCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> lotteryNode() {
        return Commands.literal("lottery")
                .requires(PermissionRequirements.path("lottery"))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.path("lottery status"))
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("help")
                        .requires(PermissionRequirements.path("lottery"))
                        .executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("bet")
                        .requires(PermissionRequirements.path("lottery bet"))
                        .then(Commands.literal("confirm")
                                .executes(ctx -> confirm(ctx.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> cancelQuote(ctx.getSource())))
                        .then(Commands.literal("now")
                                .then(Commands.argument("guess", IntegerArgumentType.integer(0, 4096))
                                        .executes(ctx -> betNow(ctx, 1, false))
                                        .then(Commands.argument("tickets", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> betNow(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "tickets"), false))
                                                .then(Commands.literal("--exact")
                                                        .executes(ctx -> betNow(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "tickets"),
                                                                true))))))
                        .then(Commands.argument("guess", IntegerArgumentType.integer(0, 4096))
                                .executes(ctx -> quote(ctx, 1, false))
                                .then(Commands.argument("tickets", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> quote(ctx,
                                                IntegerArgumentType.getInteger(ctx, "tickets"), false))
                                        .then(Commands.literal("--exact")
                                                .executes(ctx -> quote(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "tickets"), true))))))
                .then(Commands.literal("collect")
                        .requires(PermissionRequirements.path("lottery collect"))
                        .executes(ctx -> collect(ctx.getSource())))
                .then(Commands.literal("list")
                        .requires(PermissionRequirements.path("lottery list"))
                        .executes(ctx -> list(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> list(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("value")
                        .requires(PermissionRequirements.path("lottery value"))
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> value(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item")))))
                .then(Commands.literal("gold")
                        .requires(PermissionRequirements.path("lottery gold"))
                        .executes(ctx -> goldStatus(ctx.getSource()))
                        .then(Commands.literal("set")
                                .requires(PermissionRequirements.path("lottery gold set"))
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> goldSet(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "price"))))))
                .then(Commands.literal("insure")
                        .requires(PermissionRequirements.path("lottery insure"))
                        .executes(ctx -> insure(ctx.getSource()))
                        .then(Commands.literal("entity")
                                .executes(ctx -> insureEntity(ctx.getSource()))))
                .then(Commands.literal("selftest")
                        .requires(PermissionRequirements.path("lottery selftest"))
                        .executes(ctx -> insuranceSelfTest(ctx.getSource())))
                .then(Commands.literal("insurance")
                        .requires(PermissionRequirements.path("lottery insurance"))
                        .executes(ctx -> insuranceList(ctx.getSource()))
                        .then(Commands.literal("list")
                                .requires(PermissionRequirements.path("lottery insurance list"))
                                .executes(ctx -> insuranceList(ctx.getSource())))
                        .then(Commands.literal("claim")
                                .requires(PermissionRequirements.path("lottery insurance claim"))
                                .executes(ctx -> insuranceClaim(ctx.getSource(), ""))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> insuranceClaim(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("pending")
                                .requires(PermissionRequirements.path("lottery insurance pending"))
                                .executes(ctx -> insurancePending(ctx.getSource())))
                        .then(Commands.literal("approve")
                                .requires(PermissionRequirements.path("lottery insurance approve"))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> insuranceApprove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("draw")
                        .requires(PermissionRequirements.path("lottery draw"))
                        .executes(ctx -> draw(ctx.getSource())))
                .then(Commands.literal("cancel")
                        .requires(PermissionRequirements.path("lottery cancel"))
                        .executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(PermissionRequirements.path("lottery reload"))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("fund")
                        .requires(PermissionRequirements.path("lottery fund"))
                        .executes(ctx -> fundList(ctx.getSource()))
                        .then(Commands.literal("list")
                                .requires(PermissionRequirements.path("lottery fund list"))
                                .executes(ctx -> fundList(ctx.getSource())))
                        .then(Commands.literal("withdraw")
                                .requires(PermissionRequirements.path("lottery fund withdraw"))
                                .then(Commands.argument("index", IntegerArgumentType.integer(0, 100_000))
                                        .executes(ctx -> fundWithdraw(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"))))));
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        Map<String, Object> model = LotteryService.getInstance().statusModel(player);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dayLabel", String.valueOf(model.get("bettingDay")));
        values.put("drawTime", String.valueOf(model.get("drawTime")));
        values.put("cutoffTime", String.valueOf(model.get("cutoff")));
        values.put("potLine", ItemValueService.getInstance().format(number(model.get("pot"))) + " VP");
        values.put("ticketCount", String.valueOf(model.get("tickets")));
        values.put("playerCount", String.valueOf(model.get("players")));
        values.put("myTickets", String.valueOf(model.get("myTickets")));
        Object last = model.get("last");
        values.put("lastResultLine", last == null ? "上期：暂无记录。夜之城还没开始死人。"
                : "上期：" + ((Map<?, ?>) last).get("deathCount") + " 个，"
                + ((Map<?, ?>) last).get("winnerCount") + " 人中奖，奖池 "
                + ItemValueService.getInstance().format(number(((Map<?, ?>) last).get("pot"))) + " VP");
        ChatUi.send(source, OutputRenderer.render("lottery.status", values, source, null));
        return 1;
    }

    private static int quote(CommandContext<CommandSourceStack> ctx, int tickets, boolean exact) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return error(ctx.getSource(), "下注需要玩家身份。");
        }
        int guess = IntegerArgumentType.getInteger(ctx, "guess");
        LotteryService.QuoteOutcome outcome = LotteryService.getInstance()
                .quote(player, guess, tickets, exact);
        if (!outcome.ok()) {
            return error(ctx.getSource(), outcome.error());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dayLabel", outcome.dayId());
        values.put("guess", String.valueOf(outcome.guess()));
        values.put("tickets", String.valueOf(outcome.tickets()));
        values.put("affordable", String.valueOf(outcome.affordable()));
        values.put("itemLine", outcome.itemName() + " × " + outcome.handCount());
        values.put("unitLine", ItemValueService.getInstance().format(outcome.unit()) + " VP");
        values.put("availableLine", ItemValueService.getInstance().format(outcome.available()) + " VP");
        values.put("costLine", ItemValueService.getInstance().format(outcome.required()) + " VP");
        values.put("consumeLine", outcome.take() + " × " + outcome.itemName());
        values.put("overpayLine", outcome.overpay() > 0.001
                ? "超额 " + ItemValueService.getInstance().format(outcome.overpay())
                  + " VP 将进入奖池（不找零）。"
                : "无超额。");
        values.put("potLine", ItemValueService.getInstance().format(outcome.pot()) + " VP");
        values.put("drawTime", LotteryConfig.getInstance().drawTime());
        values.put("cutoffTime", LotteryConfig.getInstance().cutoffTime());
        ChatUi.send(ctx.getSource(), OutputRenderer.render("lottery.quote", values, ctx.getSource(), null));
        return 1;
    }

    private static int confirm(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return error(source, "确认下注需要玩家身份。");
        }
        return sendBetResult(source, LotteryService.getInstance().confirm(player));
    }

    private static int betNow(CommandContext<CommandSourceStack> ctx, int tickets, boolean exact) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return error(ctx.getSource(), "下注需要玩家身份。");
        }
        int guess = IntegerArgumentType.getInteger(ctx, "guess");
        return sendBetResult(ctx.getSource(), LotteryService.getInstance()
                .betNow(player, guess, tickets, exact));
    }

    private static int cancelQuote(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return error(source, "取消下注需要玩家身份。");
        }
        return LotteryService.getInstance().cancelQuote(player)
                ? reply(source, "已取消本次下注报价，未扣除物品。")
                : reply(source, "当前没有待确认的下注报价。");
    }

    private static int sendBetResult(CommandSourceStack source, LotteryService.BetOutcome outcome) {
        if (!outcome.ok()) {
            return error(source, outcome.error());
        }
        ServerPlayer player = source.getPlayer();
        if (player != null && outcome.paidEncoded() != null && !outcome.paidEncoded().isBlank()) {
            // 在 LotteryService 锁之外调用，避免与保险理赔形成锁顺序反转。
            InsuranceService.getInstance().onItemUsedAsBet(outcome.paidEncoded(),
                    player.getUUID().toString());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dayLabel", outcome.dayId());
        values.put("guess", String.valueOf(outcome.guess()));
        values.put("tickets", String.valueOf(outcome.tickets()));
        values.put("costLine", ItemValueService.getInstance().format(outcome.paid()) + " VP");
        values.put("potLine", ItemValueService.getInstance().format(outcome.pot()) + " VP");
        values.put("drawTime", LotteryConfig.getInstance().drawTime());
        values.put("cutoffTime", LotteryConfig.getInstance().cutoffTime());
        ChatUi.send(source, OutputRenderer.render("lottery.bet", values, source, null));
        if (outcome.overpay() > 0.001) {
            ChatUi.send(source, OutputRenderer.render("lottery.reply", Map.of(
                    "message", "本次投入 " + ItemValueService.getInstance().format(outcome.paid())
                            + " VP，票价 " + ItemValueService.getInstance().format(
                            LotteryConfig.getInstance().ticketPrice() * outcome.tickets())
                            + " VP，多余 " + ItemValueService.getInstance().format(outcome.overpay())
                            + " VP 进入奖池。"), source, null));
        }
        return 1;
    }

    private static int collect(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return error(source, "领取需要玩家身份。");
        }
        LotteryService.CollectOutcome outcome = LotteryService.getInstance().collect(player);
        if (!outcome.ok()) {
            return error(source, outcome.error());
        }
        if (outcome.items().isEmpty() && !outcome.remaining()) {
            return reply(source, "没有待领取的奖金。");
        }
        if (outcome.items().isEmpty() && outcome.remaining()) {
            return reply(source, "背包空间不足，物品仍保留在待领取；腾出空间后再执行 /samsara lottery collect。");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("itemsLine", summarize(outcome.items()));
        values.put("remainingLine", outcome.remaining()
                ? "背包空间不足，剩余物品仍保留在待领取；腾出空间后再执行 /samsara lottery collect。"
                : "");
        ChatUi.send(source, OutputRenderer.render("lottery.collect", values, source, null));
        return 1;
    }

    private static int list(CommandSourceStack source, int page) {
        List<Map<String, Object>> rows = LotteryService.getInstance().historyModel(page, 8);
        if (rows.isEmpty()) {
            return reply(source, "尚无历史开奖。");
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add("· " + row.get("day") + " · 死亡 " + row.get("deathCount")
                    + " · 中奖 " + row.get("winnerCount") + " 张 · 奖池 "
                    + ItemValueService.getInstance().format(number(row.get("pot"))) + " VP"
                    + (Boolean.TRUE.equals(row.get("rollover")) ? " · 滚存" : ""));
        }
        int total = LotteryService.getInstance().historyCount();
        int pages = Math.max(1, (total + 7) / 8);
        lines.add("--- 第 " + page + "/" + pages + " 页，共 " + total + " 条 ---");
        return reply(source, String.join("\n", lines));
    }

    private static int value(CommandSourceStack source, String raw) {
        String id = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            return error(source, "物品 ID 不合法：" + raw);
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        ItemStack stack = new ItemStack(item);
        double unit = ItemValueService.getInstance().unitValue(stack);
        if (unit <= 0.0) {
            return reply(source, "未定价：" + key + "（价值 0，不收）");
        }
        String tags = (stack.isDamageableItem() ? "（含耐久/附魔系数）" : "")
                + (ItemValueService.getInstance().isGoldLinked(key.toString()) ? "（随国际金价）" : "");
        return reply(source, key + " = " + ItemValueService.getInstance().format(unit)
                + " VP / 个" + tags);
    }

    private static int goldStatus(CommandSourceStack source) {
        ItemValueService values = ItemValueService.getInstance();
        double price = GoldPriceService.getInstance().currentUsdPerOunce();
        long updatedAt = GoldPriceService.getInstance().updatedAt();
        return reply(source, "国际金价：" + String.format(java.util.Locale.ROOT, "%.2f", price)
                + " USD/oz" + (updatedAt > 0 ? "（" + java.time.Instant.ofEpochMilli(updatedAt) + "）" : "（基准值）")
                + "\n金块 " + values.format(values.unitValue("minecraft:gold_block")) + " VP"
                + " · 金锭 " + values.format(values.unitValue("minecraft:gold_ingot")) + " VP"
                + " · 金粒 " + values.format(values.unitValue("minecraft:gold_nugget")) + " VP");
    }

    private static int goldSet(CommandSourceStack source, double price) {
        GoldPriceService.getInstance().setManualPrice(price, "manual");
        return goldStatus(source);
    }

    private static int insure(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return error(source, "投保需要玩家身份。");
        com.shenqianye.samsara.insurance.InsuranceService.Result result =
                com.shenqianye.samsara.insurance.InsuranceService.getInstance().insureItem(player);
        if (!result.ok()) return error(source, result.message());
        return reply(source, result.message() + "\n主手物品已记录快照；保费从副手扣除。");
    }

    private static int insureEntity(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return error(source, "投保需要玩家身份。");
        var result = com.shenqianye.samsara.insurance.InsuranceService.getInstance().insureEntity(player);
        if (!result.ok()) return error(source, result.message());
        return reply(source, result.message() + "\n生物死亡后 3 秒在主人身边复活。");
    }

    private static int insuranceSelfTest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return error(source, "自测需要玩家身份。");
        var result = com.shenqianye.samsara.insurance.InsuranceService.getInstance().selfTest(player);
        return result.ok() ? reply(source, result.message()) : error(source, result.message());
    }

    private static int insuranceList(CommandSourceStack source) {        ServerPlayer player = source.getPlayer();
        if (player == null) return error(source, "需要玩家身份。");
        var list = com.shenqianye.samsara.insurance.InsuranceService.getInstance().policies(player);
        if (list.isEmpty()) return reply(source, "你还没有保单。");
        List<String> lines = new ArrayList<>();
        for (var policy : list) {
            lines.add("· " + policy.id + " · " + policy.status + " · 价值 "
                    + ItemValueService.getInstance().format(policy.value) + " VP · 到期 "
                    + java.time.Instant.ofEpochMilli(policy.expiresAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate());
        }
        return reply(source, String.join("\n", lines));
    }

    private static int insuranceClaim(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return error(source, "理赔需要玩家身份。");
        var service = com.shenqianye.samsara.insurance.InsuranceService.getInstance();
        String policyId = id;
        if (policyId == null || policyId.isBlank()) {
            var list = service.policies(player);
            if (list.isEmpty()) return error(source, "你还没有保单。");
            policyId = list.get(0).id;
        }
        var result = service.claim(player, policyId);
        if (!result.ok()) return error(source, result.message());
        return reply(source, result.message());
    }

    private static int insurancePending(CommandSourceStack source) {
        var model = com.shenqianye.samsara.insurance.InsuranceService.getInstance().model();
        Object rows = model.get("policies");
        if (!(rows instanceof List<?> list) || list.isEmpty()) return reply(source, "没有保单。");
        List<String> lines = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map && "PENDING".equals(String.valueOf(map.get("status")))) {
                lines.add("· " + map.get("id") + " · " + map.get("owner") + " · 价值 "
                        + ItemValueService.getInstance().format(number(map.get("value"))) + " VP");
            }
        }
        return reply(source, lines.isEmpty() ? "没有待审核理赔。" : String.join("\n", lines));
    }

    private static int insuranceApprove(CommandSourceStack source, String id) {
        var result = com.shenqianye.samsara.insurance.InsuranceService.getInstance().approve(id);
        return result.ok() ? reply(source, result.message()) : error(source, result.message());
    }

    private static int draw(CommandSourceStack source) {
        if (!ConfirmGuard.confirm("lottery.draw")) {
            return reply(source, "⚠ 手动开奖确认：再次输入同一命令（"
                    + ConfirmGuard.ttlSeconds() + " 秒内）才会执行。");
        }
        return reply(source, LotteryService.getInstance().drawNow());
    }

    private static int cancel(CommandSourceStack source) {
        if (!ConfirmGuard.confirm("lottery.cancel")) {
            return reply(source, "⚠ 取消并退款确认：再次输入同一命令（"
                    + ConfirmGuard.ttlSeconds() + " 秒内）才会执行。");
        }
        return reply(source, LotteryService.getInstance().cancelNow());
    }

    private static int reload(CommandSourceStack source) {
        com.shenqianye.samsara.config.ConfigManager.getInstance()
                .reload(com.shenqianye.samsara.config.ConfigSchema.LOTTERY);
        String result = LotteryConfig.getInstance().reload();
        ItemValueService.setGoldCraftedLinkEnabled(LotteryConfig.getInstance().goldCraftLink());
        return reply(source, result);
    }

    private static int fundList(CommandSourceStack source) {
        Map<String, Object> model = LotteryService.getInstance().fundModel();
        Object items = model.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            return reply(source, "服务器基金为空。");
        }
        List<String> lines = new ArrayList<>();
        lines.add("服务器基金 · 总价值 " + ItemValueService.getInstance().format(number(model.get("value"))) + " VP");
        int index = 0;
        for (Object value : list) {
            if (value instanceof Map<?, ?> item) {
                lines.add("[" + index + "] " + item.get("count") + " × " + item.get("item")
                        + " · " + ItemValueService.getInstance().format(number(item.get("value"))) + " VP");
            }
            index++;
        }
        return reply(source, String.join("\n", lines));
    }

    private static int fundWithdraw(CommandSourceStack source, int index) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return error(source, "控制台不能领取基金物品。");
        }
        if (!ConfirmGuard.confirm("lottery.fund.withdraw." + index)) {
            return reply(source, "⚠ 基金提取确认：再次输入同一命令（"
                    + ConfirmGuard.ttlSeconds() + " 秒内）才会执行。");
        }
        return reply(source, LotteryService.getInstance().withdrawFund(player, index));
    }

    private static int help(CommandSourceStack source) {
        ChatUi.send(source, OutputRenderer.render("lottery.help", Map.of(), source, null));
        return 1;
    }

    private static int reply(CommandSourceStack source, String message) {
        ChatUi.send(source, OutputRenderer.render("lottery.reply", Map.of("message", message), source, null));
        return 1;
    }

    private static int error(CommandSourceStack source, String message) {
        ChatUi.send(source, OutputRenderer.render("lottery.error", Map.of("message", message), source, null));
        return 0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String summarize(List<ItemStack> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            counts.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getValue() + " × " + e.getKey());
        }
        return String.join("、", parts);
    }
}
