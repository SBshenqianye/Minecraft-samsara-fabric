package com.shenqianye.samsara.lottery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 死人乐透（Body Lotto）核心服务。
 *
 * <p>规则：每天一轮，猜结算日的死亡人数；结算日 00:00 截止下注（盲押），
 * 次日 07:00 开奖；精准匹配，中奖票按票平分，5% 进 fund，无人中全额滚存。
 *
 * <p>所有支付与奖池都是实物 ItemStack；本类不维护玩家 VP 余额。
 */
public final class LotteryService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SCHEMA = "1";

    private static LotteryService instance;

    private MinecraftServer server;
    private Path root;
    private RegistryAccess registries;
    private StateData state = new StateData();
    private List<String> fund = new ArrayList<>();
    private List<ClaimData> claims = new ArrayList<>();
    private final Map<String, DayData> dayCache = new LinkedHashMap<>();
    private int tickCounter;

    private LotteryService() {
    }

    public static synchronized LotteryService getInstance() {
        if (instance == null) {
            instance = new LotteryService();
        }
        return instance;
    }

    // ---- 生命周期 ----

    public synchronized void init(MinecraftServer server) {
        this.server = server;
        this.registries = server.registryAccess();
        this.root = StoragePaths.get().worldDataRoot().resolve("lottery");
        try {
            Files.createDirectories(root.resolve("days"));
            Files.createDirectories(root.resolve("audit"));
        } catch (IOException e) {
            Samsara.LOGGER.warn("[lottery] 无法创建乐透数据目录：{}", e.getMessage());
        }
        loadState();
        loadFund();
        loadClaims();
        Samsara.LOGGER.info("[lottery] 已加载 · 模式 {} · 数据 {}", dayMode(), root);
    }

    public synchronized void tick(MinecraftServer server) {
        if (server == null || !LotteryConfig.getInstance().enabled()) {
            return;
        }
        this.server = server;
        if (registries == null) {
            registries = server.registryAccess();
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        markCovered(todayId());
        catchUp();
        if (System.currentTimeMillis() - state.lastHeartbeat > 60_000L) {
            state.lastHeartbeat = System.currentTimeMillis();
            saveState();
        }
    }

    public synchronized void onServerStopping(MinecraftServer server) {
        if (server == null) {
            return;
        }
        state.lastHeartbeat = System.currentTimeMillis();
        saveState();
        saveFund();
        saveClaims();
        for (DayData day : dayCache.values()) {
            saveDay(day);
        }
    }

    public synchronized void onJoin(ServerPlayer player) {
        int count = claimCount(player.getUUID());
        if (count > 0) {
            player.sendSystemMessage(Component.literal("[死人乐透] ▸ 你有 " + count
                    + " 笔待领取奖金，输入 /samsara lottery collect 领取。"));
        }
    }

    /**
     * 死亡计数：玩家、村民（含流浪商人）、命名怪；同一玩家可重复。
     */
    public synchronized void onDeath(LivingEntity entity, DamageSource source) {
        if (entity == null || !LotteryConfig.getInstance().enabled()) {
            return;
        }
        LotteryConfig config = LotteryConfig.getInstance();
        String name = entity.getName().getString();
        if (matchesExcluded(config.excludeNames(), name)) {
            return;
        }
        int playerDeaths = 0;
        int villagerDeaths = 0;
        int namedDeaths = 0;
        if (entity instanceof ServerPlayer) {
            if (!config.countPlayers()) {
                return;
            }
            playerDeaths = 1;
        } else if (entity instanceof Villager || entity instanceof WanderingTrader) {
            if (entity instanceof WanderingTrader && !config.countWanderingTraders()) {
                return;
            }
            if (entity instanceof Villager && !config.countVillagers()) {
                return;
            }
            villagerDeaths = 1;
        } else if (entity.hasCustomName()) {
            if (!config.countNamedMobs()) {
                return;
            }
            namedDeaths = 1;
        } else {
            return;
        }

        DayData day = loadOrCreateDay(todayId());
        if (!config.repeatDeaths() && !day.victims.add(entity.getUUID().toString())) {
            return;
        }
        day.covered = true;
        day.deathCount++;
        day.playerDeaths += playerDeaths;
        day.villagerDeaths += villagerDeaths;
        day.namedMobDeaths += namedDeaths;
        saveDay(day);
    }

    // ---- 下注 ----

    public synchronized BetOutcome bet(ServerPlayer player, int guess, int tickets, boolean exact) {
        LotteryConfig config = LotteryConfig.getInstance();
        if (!config.enabled()) {
            return BetOutcome.error("死人乐透未启用。");
        }
        if (player == null) {
            return BetOutcome.error("需要玩家身份。");
        }
        if (guess < 0 || guess > 4096) {
            return BetOutcome.error("猜测数字应在 0~4096 之间。");
        }
        if (tickets < 1 || tickets > config.maxTicketsPerPlayer()) {
            return BetOutcome.error("单次可买 1~" + config.maxTicketsPerPlayer() + " 张。");
        }
        String dayId = bettingDayId();
        DayData day = loadOrCreateDay(dayId);
        int mine = 0;
        for (BetData bet : day.bets) {
            if (bet.ownerUuid.equals(player.getUUID().toString())) {
                mine++;
            }
        }
        if (mine + tickets > config.maxTicketsPerPlayer()) {
            return BetOutcome.error("本轮你最多 " + config.maxTicketsPerPlayer()
                    + " 张，已买 " + mine + " 张。");
        }
        if (day.bets.size() + tickets > config.maxTicketsPerRound()) {
            return BetOutcome.error("本轮票数已满（" + config.maxTicketsPerRound() + " 张）。");
        }

        int slot = player.getInventory().getSelectedSlot();
        ItemStack hand = player.getInventory().getItem(slot);
        double unit = ItemValueService.getInstance().unitValue(hand);
        if (unit <= 0.0) {
            return BetOutcome.error("主手物品没有价值；请手持可估值的物品（铁锭/钻石等）。");
        }
        double required = config.ticketPrice() * tickets;
        double available = unit * hand.getCount();
        if (available + 0.0001 < required) {
            return BetOutcome.error("主手价值不足：需要 " + ItemValueService.getInstance().format(required)
                    + " VP，当前 " + ItemValueService.getInstance().format(available) + " VP。");
        }
        if (exact && available > required + 0.0001) {
            return BetOutcome.error("--exact 要求不超额：需要 " + ItemValueService.getInstance().format(required)
                    + " VP，当前 " + ItemValueService.getInstance().format(available) + " VP。");
        }
        if (day.bets.size() + tickets > 0) {
            double potValue = potValue(day) + valueOfEncoded(state.rollover);
            if (potValue + required > config.maxPotValue()) {
                return BetOutcome.error("本轮奖池已达上限 " + config.maxPotValue() + " VP。");
            }
        }

        int take = (int) Math.ceil(required / unit);
        take = Math.max(1, Math.min(take, hand.getCount()));
        double actual = unit * take;
        if (actual + 0.0001 < required) {
            return BetOutcome.error("主手物品不足以支付票价。");
        }
        ItemStack paid = hand.copyWithCount(take);
        hand.shrink(take);
        player.getInventory().setItem(slot, hand.isEmpty() ? ItemStack.EMPTY : hand);
        player.containerMenu.broadcastChanges();

        BetData bet = new BetData();
        bet.ownerUuid = player.getUUID().toString();
        bet.ownerName = player.getGameProfile().name();
        bet.dataUuid = player.getUUID().toString();
        bet.guess = guess;
        bet.value = actual;
        bet.items = LotteryItemCodec.encodeAll(List.of(paid), registries);
        bet.at = System.currentTimeMillis();
        day.bets.add(bet);
        saveDay(day);

        double over = Math.max(0.0, actual - required);
        return BetOutcome.ok(dayId, guess, tickets, actual, over, potValue(day) + valueOfEncoded(state.rollover));
    }

    public synchronized CollectOutcome collect(ServerPlayer player) {
        if (player == null) {
            return CollectOutcome.error("需要玩家身份。");
        }
        List<ClaimData> mine = new ArrayList<>();
        for (ClaimData claim : claims) {
            if (!claim.claimed && claim.ownerUuid.equals(player.getUUID().toString())) {
                mine.add(claim);
            }
        }
        if (mine.isEmpty()) {
            return CollectOutcome.ok(List.of(), 0);
        }
        List<ItemStack> given = new ArrayList<>();
        for (ClaimData claim : mine) {
            for (ItemStack stack : LotteryItemCodec.decodeAll(claim.items, registries)) {
                give(player, stack);
                given.add(stack);
            }
            claim.claimed = true;
        }
        saveClaims();
        return CollectOutcome.ok(given, mine.size());
    }

    // ---- 状态 / 历史 ----

    public synchronized Map<String, Object> statusModel(ServerPlayer player) {
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        DayData last = lastSettled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bettingDay", betting);
        out.put("countingDay", todayId());
        out.put("drawTime", drawLabel(betting));
        out.put("cutoff", cutoffLabel(betting));
        out.put("pot", potValue(day) + valueOfEncoded(state.rollover));
        out.put("tickets", day.bets.size());
        out.put("players", distinctPlayers(day));
        out.put("myTickets", myTickets(day, player));
        out.put("last", last == null ? null : resultModel(last));
        out.put("enabled", LotteryConfig.getInstance().enabled());
        return out;
    }

    public synchronized List<Map<String, Object>> historyModel(int page, int pageSize) {
        List<String> ids = listSettledDayIds();
        ids.sort((a, b) -> compareDayId(b, a));
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(ids.size(), from + pageSize);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            DayData day = loadDay(ids.get(i));
            if (day != null && day.result != null) {
                out.add(resultModel(day));
            }
        }
        return out;
    }

    public synchronized int historyCount() {
        return listSettledDayIds().size();
    }

    // ---- 管理员 ----

    public synchronized String drawNow() {
        String dayId = prevDayId(todayId());
        DayData day = loadDay(dayId);
        if (day == null) {
            day = loadOrCreateDay(dayId);
        }
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            return "该结算日已经开奖或取消：" + dayId;
        }
        settle(dayId, false, true);
        return "已开奖：" + dayId;
    }

    public synchronized String cancelNow() {
        String dayId = todayId();
        DayData day = loadOrCreateDay(dayId);
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            return "该结算日已经结束：" + dayId;
        }
        refundAll(day, "管理员取消");
        day.state = "CANCELLED";
        day.result = new ResultData();
        day.result.deathCount = day.deathCount;
        day.result.winningTickets = 0;
        day.result.potValue = 0;
        day.result.rakeValue = 0;
        day.result.rollover = false;
        day.result.nextDay = bettingDayId();
        day.result.at = System.currentTimeMillis();
        saveDay(day);
        return "已取消并退款：" + dayId;
    }

    public synchronized Map<String, Object> fundModel() {
        List<ItemStack> items = LotteryItemCodec.decodeAll(fund, registries);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", ItemValueService.getInstance().valueOf(items));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            String itemId = ItemValueService.getInstance().idOf(stack.getItem());
            item.put("id", itemId);
            item.put("item", itemId);
            item.put("name", stack.getHoverName().getString());
            item.put("icon", iconName(ItemValueService.getInstance().idOf(stack.getItem())));
            item.put("count", stack.getCount());
            item.put("value", ItemValueService.getInstance().valueOf(stack));
            ItemValueService.getInstance().applyLang(item, ItemValueService.getInstance().idOf(stack.getItem()));
            list.add(item);
        }
        out.put("items", list);
        return out;
    }

    public synchronized String withdrawFund(ServerPlayer player, int index) {
        if (player == null) {
            return "需要玩家身份；控制台不能领取基金物品。";
        }
        List<ItemStack> items = LotteryItemCodec.decodeAll(fund, registries);
        if (index < 0 || index >= items.size()) {
            return "基金序号无效：" + index;
        }
        ItemStack stack = items.remove(index);
        fund = LotteryItemCodec.encodeAll(items, registries);
        saveFund();
        give(player, stack);
        return "已从基金取出：" + stack.getCount() + " × " + stack.getHoverName().getString();
    }

    public synchronized Map<String, Object> adminModel() {
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        DayData counting = loadOrCreateDay(todayId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", LotteryConfig.getInstance().enabled());
        out.put("mode", dayMode());
        out.put("bettingDay", dayModel(day));
        out.put("countingDay", dayModel(counting));
        out.put("fund", fundModel());
        out.put("history", historyModel(1, 10));
        out.put("configPath", LotteryConfig.getInstance().configPath());
        return out;
    }

    public synchronized Map<String, Object> publicModel() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", "user");
        out.put("enabled", LotteryConfig.getInstance().enabled() && LotteryConfig.getInstance().publicVisible());
        if (!LotteryConfig.getInstance().publicVisible()) {
            return out;
        }
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        Map<String, Object> round = new LinkedHashMap<>();
        round.put("day", betting);
        round.put("drawTime", drawLabel(betting));
        round.put("cutoff", cutoffLabel(betting));
        round.put("pot", potValue(day) + valueOfEncoded(state.rollover));
        round.put("tickets", day.bets.size());
        round.put("players", distinctPlayers(day));
        out.put("round", round);
        DayData last = lastSettled();
        out.put("last", last == null ? null : publicResult(last));
        Map<String, Object> fundModel = fundModel();
        Map<String, Object> fund = new LinkedHashMap<>();
        fund.put("value", fundModel.get("value"));
        List<Map<String, Object>> publicItems = new ArrayList<>();
        if (fundModel.get("items") instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> raw)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                for (String key : List.of("id", "item", "name", "zh", "icon", "count", "value")) {
                    if (raw.containsKey(key)) item.put(key, raw.get(key));
                }
                publicItems.add(item);
            }
        }
        fund.put("count", publicItems.size());
        fund.put("items", publicItems);
        out.put("fund", fund);
        return out;
    }

    private static String iconName(String itemId) {
        if (itemId == null || itemId.isBlank()) return "barrier";
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }

    // ---- 调度 ----

    private void catchUp() {
        String today = todayId();
        String yesterday = prevDayId(today);
        if (state.lastSettledDay == null || state.lastSettledDay.isBlank()) {
            state.lastSettledDay = prevDayId(yesterday);
            saveState();
        }
        for (int guard = 0; guard < 64; guard++) {
            String candidate = nextDayId(state.lastSettledDay);
            int cmp = compareDayId(candidate, yesterday);
            if (cmp > 0) {
                return;
            }
            boolean missed = cmp < 0;
            if (!missed && !drawReached(candidate)) {
                return;
            }
            settle(candidate, missed, false);
            state.lastSettledDay = maxDay(state.lastSettledDay, candidate);
            saveState();
            if (!missed) {
                return;
            }
        }
    }

    private void markCovered(String dayId) {
        DayData day = loadOrCreateDay(dayId);
        if (!day.covered) {
            day.covered = true;
            saveDay(day);
        }
    }

    private boolean drawReached(String dayId) {
        DayData day = loadOrCreateDay(dayId);
        if (LotteryConfig.getInstance().realDay()) {
            return System.currentTimeMillis() >= day.drawAt;
        }
        return gameTime() >= day.drawAt;
    }

    private void settle(String dayId, boolean missed, boolean force) {
        DayData day = loadDay(dayId);
        if (day == null) {
            day = loadOrCreateDay(dayId);
        }
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            return;
        }
        if (!force && !drawReached(dayId)) {
            return;
        }
        if (!day.covered && !day.bets.isEmpty()) {
            refundAll(day, "统计不完整（服务器离线）");
        } else if (day.bets.isEmpty()) {
            // 没有票，只记录死亡数
        } else {
            settleWithBets(day);
        }
        day.state = "SETTLED";
        day.result = day.result == null ? new ResultData() : day.result;
        day.result.deathCount = day.deathCount;
        day.result.at = System.currentTimeMillis();
        day.result.nextDay = nextDayId(day.dayId);
        if (day.result.winnerNames == null) {
            day.result.winnerNames = new ArrayList<>();
        }
        saveDay(day);
        announce(day);
        state.lastSettledDay = maxDay(state.lastSettledDay, dayId);
        saveState();
    }

    private void settleWithBets(DayData day) {
        potTaken.clear();
        List<ItemStack> pot = new ArrayList<>();
        for (BetData bet : day.bets) {
            pot.addAll(LotteryItemCodec.decodeAll(bet.items, registries));
        }
        pot.addAll(LotteryItemCodec.decodeAll(state.rollover, registries));
        state.rollover = new ArrayList<>();

        List<BetData> winners = new ArrayList<>();
        for (BetData bet : day.bets) {
            if (bet.guess == day.deathCount) {
                winners.add(bet);
            }
        }
        ResultData result = new ResultData();
        result.deathCount = day.deathCount;
        result.potValue = ItemValueService.getInstance().valueOf(pot);
        result.winningTickets = winners.size();
        result.winnerNames = new ArrayList<>();
        result.at = System.currentTimeMillis();
        result.nextDay = nextDayId(day.dayId);
        result.rollover = false;

        if (winners.isEmpty()) {
            state.rollover = LotteryItemCodec.encodeAll(pot, registries);
            result.rollover = true;
            day.result = result;
            saveState();
            return;
        }

        double rake = result.potValue * LotteryConfig.getInstance().rakePercent() / 100.0;
        double actualRake = takeValue(pot, rake);
        result.rakeValue = actualRake;
        if ("fund".equalsIgnoreCase(LotteryConfig.getInstance().rakeDestination())) {
            fund.addAll(LotteryItemCodec.encodeAll(potTaken, registries));
            potTaken.clear();
            saveFund();
        } else if ("rollover".equalsIgnoreCase(LotteryConfig.getInstance().rakeDestination())) {
            List<String> rollover = new ArrayList<>(state.rollover == null ? List.of() : state.rollover);
            rollover.addAll(LotteryItemCodec.encodeAll(potTaken, registries));
            state.rollover = rollover;
            potTaken.clear();
        } else {
            potTaken.clear();
        }

        List<Payee> payees = payees(winners);
        distribute(pot, payees);
        for (Payee payee : payees) {
            result.winnerNames.add(payee.name);
            deliver(payee, result.deathCount);
        }
        // 未分配的余数滚存
        if (!pot.isEmpty()) {
            state.rollover = LotteryItemCodec.encodeAll(pot, registries);
        }
        day.result = result;
        saveState();
        saveClaims();
    }

    /** takeValue 的临时收集器：避免把 fund 与“实际抽走”混在一起。 */
    private final List<ItemStack> potTaken = new ArrayList<>();

    private double takeValue(List<ItemStack> pot, double target) {
        if (target <= 0.0) {
            return 0.0;
        }
        pot.sort(Comparator.comparingDouble(s -> ItemValueService.getInstance().unitValue(s)));
        double taken = 0.0;
        for (int i = 0; i < pot.size() && taken < target; i++) {
            ItemStack stack = pot.get(i);
            double unit = ItemValueService.getInstance().unitValue(stack);
            if (unit <= 0.0 || unit > target - taken + 1.0e-6) {
                // 实物不可拆分：宁可少抽，也不要把整个奖品抽走
                continue;
            }
            int need = (int) Math.floor((target - taken) / unit);
            int take = Math.max(1, Math.min(stack.getCount(), need));
            ItemStack piece = stack.copyWithCount(take);
            stack.shrink(take);
            taken += unit * take;
            potTaken.add(piece);
            if (stack.isEmpty()) {
                pot.remove(i);
                i--;
            }
        }
        return taken;
    }

    private List<Payee> payees(List<BetData> winners) {
        if ("player".equalsIgnoreCase(LotteryConfig.getInstance().splitMode())) {
            Map<String, Payee> map = new LinkedHashMap<>();
            for (BetData bet : winners) {
                Payee payee = map.computeIfAbsent(bet.dataUuid,
                        key -> new Payee(bet.ownerUuid, bet.ownerName));
                payee.tickets++;
            }
            return new ArrayList<>(map.values());
        }
        List<Payee> out = new ArrayList<>();
        for (BetData bet : winners) {
            Payee payee = new Payee(bet.ownerUuid, bet.ownerName);
            payee.tickets = 1;
            out.add(payee);
        }
        return out;
    }

    private void distribute(List<ItemStack> pot, List<Payee> payees) {
        if (payees.isEmpty()) {
            return;
        }
        double remaining = 0.0;
        for (ItemStack stack : pot) {
            remaining += ItemValueService.getInstance().valueOf(stack);
        }
        double targetValue = remaining / payees.size();
        // 高价值物品优先；每次给当前份额最低的中奖者；低价值物品补零头。
        pot.sort(Comparator.comparingDouble(
                (ItemStack s) -> ItemValueService.getInstance().unitValue(s)).reversed());
        for (ItemStack stack : new ArrayList<>(pot)) {
            while (!stack.isEmpty()) {
                Payee target = payees.stream()
                        .min(Comparator.comparingDouble(p -> p.value))
                        .orElse(payees.get(0));
                double unit = ItemValueService.getInstance().unitValue(stack);
                if (unit <= 0.0) {
                    target.items.add(stack.copyWithCount(1));
                    stack.shrink(1);
                    continue;
                }
                double gap = Math.max(unit, targetValue - target.value);
                int take = (int) Math.max(1, Math.min(stack.getCount(), Math.ceil(gap / unit)));
                ItemStack piece = stack.copyWithCount(take);
                target.items.add(piece);
                target.value += unit * take;
                stack.shrink(take);
            }
            pot.remove(stack);
        }
    }

    private void deliver(Payee payee, int deathCount) {
        ServerPlayer online = online(payee.ownerUuid);
        if (online != null) {
            for (ItemStack stack : payee.items) {
                give(online, stack);
            }
            online.sendSystemMessage(OutputRenderer.render("lottery.win",
                    Map.of("deathCount", String.valueOf(deathCount),
                            "guess", "?",
                            "shareLine", summarize(payee.items),
                            "claimLine", "奖金已放入背包。"), null, null));
            return;
        }
        ClaimData claim = new ClaimData();
        claim.ownerUuid = payee.ownerUuid;
        claim.ownerName = payee.name;
        claim.dataUuid = payee.ownerUuid;
        claim.reason = "中奖";
        claim.items = LotteryItemCodec.encodeAll(payee.items, registries);
        claim.at = System.currentTimeMillis();
        claim.claimed = false;
        claims.add(claim);
    }

    private void refundAll(DayData day, String reason) {
        for (BetData bet : day.bets) {
            List<ItemStack> items = LotteryItemCodec.decodeAll(bet.items, registries);
            ServerPlayer online = online(bet.ownerUuid);
            if (online != null) {
                for (ItemStack stack : items) {
                    give(online, stack);
                }
            } else {
                ClaimData claim = new ClaimData();
                claim.ownerUuid = bet.ownerUuid;
                claim.ownerName = bet.ownerName;
                claim.dataUuid = bet.dataUuid;
                claim.reason = reason;
                claim.items = bet.items;
                claim.at = System.currentTimeMillis();
                claim.claimed = false;
                claims.add(claim);
            }
        }
        day.bets.clear();
        saveClaims();
    }

    private void announce(DayData day) {
        if (server == null || day.result == null) {
            return;
        }
        String cause = day.playerDeaths + " 名玩家、" + day.villagerDeaths + " 名村民、"
                + day.namedMobDeaths + " 只命名怪";
        String winnerLine = day.result.winningTickets == 0
                ? "无人猜中，奖池滚入下一轮。"
                : "中奖 " + day.result.winningTickets + " 张票。";
        Component message = OutputRenderer.render("lottery.result", Map.of(
                "periodLabel", LotteryConfig.getInstance().realDay() ? "昨天" : "上一轮",
                "deathCount", String.valueOf(day.result.deathCount),
                "winnerCount", String.valueOf(day.result.winningTickets),
                "potLine", ItemValueService.getInstance().format(day.result.potValue) + " VP",
                "causeLine", "多亏了没完没了的死亡，全服贡献了 " + cause + "。",
                "flavorLine", flavor(day.result.deathCount, day.result.winningTickets),
                "winnerLine", winnerLine,
                "nextDraw", day.result.nextDay,
                "coverageLine", day.covered ? "" : "⚠ 本轮统计不完整。"
        ), null, null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
        Samsara.LOGGER.info("[lottery] 开奖 {} · 死亡 {} · 票 {} · 奖池 {}",
                day.dayId, day.result.deathCount, day.result.winningTickets, day.result.potValue);
    }

    private String flavor(int deathCount, int winnerCount) {
        if (deathCount == 0) {
            return "零死亡。夜之城难得安静了一次。";
        }
        if (deathCount <= 2) {
            return "才 " + deathCount + " 个？太平洲都比这热闹。";
        }
        if (deathCount <= 9) {
            return "满打满算 " + deathCount + " 个，停尸房的冷柜勉强够用。";
        }
        if (deathCount <= 19) {
            return "满打满算 " + deathCount + " 个，停尸房的冷柜又不够用了。";
        }
        return "满打满算 " + deathCount + " 个！NCPD 今天要加班，创伤小组要加价。";
    }

    // ---- 时间 / 日 ----

    public String todayId() {
        if (LotteryConfig.getInstance().realDay()) {
            return "real-" + LocalDate.now(ZoneId.systemDefault());
        }
        long index = Math.floorDiv(gameTime() + 6000L, 24000L);
        return "game-" + index;
    }

    public String bettingDayId() {
        return nextDayId(todayId());
    }

    private long gameTime() {
        if (server == null || server.overworld() == null) {
            return 0L;
        }
        return server.overworld().getGameTime();
    }

    private String nextDayId(String dayId) {
        if (dayId.startsWith("game-")) {
            return "game-" + (gameIndex(dayId) + 1);
        }
        if (dayId.startsWith("real-")) {
            LocalDate date = LocalDate.parse(dayId.substring(5));
            return "real-" + date.plusDays(1);
        }
        return dayId;
    }

    private String prevDayId(String dayId) {
        if (dayId.startsWith("game-")) {
            return "game-" + (gameIndex(dayId) - 1);
        }
        if (dayId.startsWith("real-")) {
            LocalDate date = LocalDate.parse(dayId.substring(5));
            return "real-" + date.minusDays(1);
        }
        return dayId;
    }

    private long gameIndex(String dayId) {
        try {
            return Long.parseLong(dayId.substring("game-".length()));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private int compareDayId(String a, String b) {
        if (a.startsWith("game-") && b.startsWith("game-")) {
            return Long.compare(gameIndex(a), gameIndex(b));
        }
        if (a.startsWith("real-") && b.startsWith("real-")) {
            return LocalDate.parse(a.substring(5)).compareTo(LocalDate.parse(b.substring(5)));
        }
        return a.compareTo(b);
    }

    private String maxDay(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return compareDayId(a, b) >= 0 ? a : b;
    }

    private String dayMode() {
        return LotteryConfig.getInstance().realDay() ? "real" : "game";
    }

    private String drawLabel(String dayId) {
        DayData day = loadOrCreateDay(dayId);
        if (LotteryConfig.getInstance().realDay()) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(day.drawAt),
                    ZoneId.systemDefault()).toLocalDate() + " "
                    + LotteryConfig.getInstance().drawTime();
        }
        return "第 " + gameIndex(dayId) + " 游戏日 " + LotteryConfig.getInstance().drawTime();
    }

    private String cutoffLabel(String dayId) {
        DayData day = loadOrCreateDay(dayId);
        if (LotteryConfig.getInstance().realDay()) {
            return LocalDate.parse(dayId.substring(5)) + " " + LotteryConfig.getInstance().cutoffTime();
        }
        return "第 " + gameIndex(dayId) + " 游戏日 " + LotteryConfig.getInstance().cutoffTime();
    }

    // ---- 持久化 ----

    private DayData loadOrCreateDay(String dayId) {
        DayData cached = dayCache.get(dayId);
        if (cached != null) {
            return cached;
        }
        DayData day = loadDay(dayId);
        if (day == null) {
            day = new DayData();
            day.dayId = dayId;
            fillWindow(day);
        }
        dayCache.put(dayId, day);
        return day;
    }

    private void fillWindow(DayData day) {
        LotteryConfig config = LotteryConfig.getInstance();
        if (config.realDay()) {
            LocalDate date = LocalDate.parse(day.dayId.substring(5));
            ZoneId zone = ZoneId.systemDefault();
            day.startAt = date.atStartOfDay(zone).toInstant().toEpochMilli();
            day.endAt = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            day.drawAt = date.plusDays(1).atTime(config.drawLocalTime())
                    .atZone(zone).toInstant().toEpochMilli();
        } else {
            long index = gameIndex(day.dayId);
            day.startAt = index * 24000L - 6000L;
            day.endAt = day.startAt + 24000L;
            // 游戏内 00:00 是 tick 18000；07:00 距 00:00 为 7000 ticks。
            long drawTicks = Math.round(config.drawLocalTime().toSecondOfDay()
                    / 86400.0 * 24000.0);
            day.drawAt = day.endAt + drawTicks;
        }
    }

    private DayData loadDay(String dayId) {
        DayData cached = dayCache.get(dayId);
        if (cached != null) {
            return cached;
        }
        if (root == null) {
            return null;
        }
        Path file = root.resolve("days").resolve(dayId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            DayData day = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DayData.class);
            if (day != null) {
                dayCache.put(dayId, day);
            }
            return day;
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[lottery] 读取日数据失败 {}：{}", dayId, e.getMessage());
            return null;
        }
    }

    private void saveDay(DayData day) {
        if (root == null || day == null) {
            return;
        }
        dayCache.put(day.dayId, day);
        write(root.resolve("days").resolve(day.dayId + ".json"), GSON.toJson(day));
    }

    private void loadState() {
        if (root == null) {
            return;
        }
        Path file = root.resolve("state.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            StateData loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StateData.class);
            if (loaded != null) {
                state = loaded;
                if (state.rollover == null) {
                    state.rollover = new ArrayList<>();
                }
            }
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[lottery] 读取 state 失败：{}", e.getMessage());
        }
    }

    private void saveState() {
        if (root == null) {
            return;
        }
        state.schemaVersion = SCHEMA;
        write(root.resolve("state.json"), GSON.toJson(state));
    }

    private void loadFund() {
        if (root == null) {
            return;
        }
        Path file = root.resolve("fund.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            FundData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FundData.class);
            if (data != null && data.items != null) {
                fund = new ArrayList<>(data.items);
            }
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[lottery] 读取基金失败：{}", e.getMessage());
        }
    }

    private void saveFund() {
        if (root == null) {
            return;
        }
        FundData data = new FundData();
        data.items = new ArrayList<>(fund);
        write(root.resolve("fund.json"), GSON.toJson(data));
    }

    private void loadClaims() {
        if (root == null) {
            return;
        }
        Path file = root.resolve("claims.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ClaimList data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClaimList.class);
            if (data != null && data.claims != null) {
                claims = new ArrayList<>(data.claims);
            }
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[lottery] 读取领取记录失败：{}", e.getMessage());
        }
    }

    private void saveClaims() {
        if (root == null) {
            return;
        }
        ClaimList data = new ClaimList();
        data.claims = new ArrayList<>(claims);
        write(root.resolve("claims.json"), GSON.toJson(data));
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[lottery] 写入 {} 失败：{}", file, e.getMessage());
        }
    }

    // ---- 工具 ----

    private boolean matchesExcluded(List<String> patterns, String name) {
        if (patterns == null || patterns.isEmpty() || name == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (pattern.endsWith("*")) {
                if (name.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (name.equalsIgnoreCase(pattern)) {
                return true;
            }
        }
        return false;
    }

    private ServerPlayer online(String uuid) {
        if (server == null || uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuid));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void give(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack.copy())) {
            player.drop(stack.copy(), false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        player.containerMenu.broadcastChanges();
    }

    private double potValue(DayData day) {
        double total = 0.0;
        for (BetData bet : day.bets) {
            total += bet.value;
        }
        return total;
    }

    private double valueOfEncoded(List<String> encoded) {
        return ItemValueService.getInstance().valueOf(LotteryItemCodec.decodeAll(encoded, registries));
    }

    private int distinctPlayers(DayData day) {
        Set<String> set = new LinkedHashSet<>();
        for (BetData bet : day.bets) {
            set.add(bet.dataUuid);
        }
        return set.size();
    }

    private String myTickets(DayData day, ServerPlayer player) {
        if (player == null) {
            return "控制台无票";
        }
        List<String> parts = new ArrayList<>();
        for (BetData bet : day.bets) {
            if (bet.ownerUuid.equals(player.getUUID().toString())) {
                parts.add(bet.guess + " 号");
            }
        }
        return parts.isEmpty() ? "暂无" : String.join("、", parts);
    }

    private DayData lastSettled() {
        List<String> ids = listSettledDayIds();
        if (ids.isEmpty()) {
            return null;
        }
        ids.sort((a, b) -> compareDayId(b, a));
        for (String id : ids) {
            DayData day = loadDay(id);
            if (day != null && day.result != null && "SETTLED".equals(day.state)) {
                return day;
            }
        }
        return null;
    }

    private List<String> listSettledDayIds() {
        List<String> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        Path dir = root.resolve("days");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        out.add(name.substring(0, name.length() - ".json".length()));
                    });
        } catch (IOException e) {
            Samsara.LOGGER.warn("[lottery] 扫描历史失败：{}", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> dayModel(DayData day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.dayId);
        out.put("state", day.state);
        out.put("covered", day.covered);
        out.put("deathCount", day.deathCount);
        out.put("playerDeaths", day.playerDeaths);
        out.put("villagerDeaths", day.villagerDeaths);
        out.put("namedMobDeaths", day.namedMobDeaths);
        out.put("tickets", day.bets.size());
        out.put("pot", potValue(day) + valueOfEncoded(state.rollover));
        out.put("drawTime", drawLabel(day.dayId));
        out.put("cutoff", cutoffLabel(day.dayId));
        List<Map<String, Object>> bets = new ArrayList<>();
        for (BetData bet : day.bets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", bet.ownerName);
            item.put("guess", bet.guess);
            item.put("value", bet.value);
            bets.add(item);
        }
        out.put("bets", bets);
        return out;
    }

    private Map<String, Object> resultModel(DayData day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.dayId);
        out.put("deathCount", day.result == null ? day.deathCount : day.result.deathCount);
        out.put("winnerCount", day.result == null ? 0 : day.result.winningTickets);
        out.put("pot", day.result == null ? 0 : day.result.potValue);
        out.put("rake", day.result == null ? 0 : day.result.rakeValue);
        out.put("rollover", day.result != null && day.result.rollover);
        out.put("winners", day.result == null ? List.of() : day.result.winnerNames);
        out.put("at", day.result == null ? 0 : day.result.at);
        return out;
    }

    private Map<String, Object> publicResult(DayData day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.dayId);
        out.put("deathCount", day.result == null ? day.deathCount : day.result.deathCount);
        out.put("winnerCount", day.result == null ? 0 : day.result.winningTickets);
        out.put("pot", day.result == null ? 0 : day.result.potValue);
        out.put("rollover", day.result != null && day.result.rollover);
        List<String> masked = new ArrayList<>();
        if (day.result != null && day.result.winnerNames != null) {
            for (String name : day.result.winnerNames) {
                masked.add(mask(name));
            }
        }
        out.put("winners", masked);
        return out;
    }

    private int claimCount(UUID uuid) {
        int count = 0;
        for (ClaimData claim : claims) {
            if (!claim.claimed && claim.ownerUuid.equals(uuid.toString())) {
                count++;
            }
        }
        return count;
    }

    private String summarize(List<ItemStack> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            String name = stack.getHoverName().getString();
            counts.merge(name, stack.getCount(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getValue() + " × " + e.getKey());
        }
        return String.join("、", parts);
    }

    private static String mask(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        int length = name.length();
        if (length <= 3) {
            return name.charAt(0) + "*".repeat(Math.max(0, length - 1));
        }
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean keep = i == 0 || i >= length - 3 || !Character.isLetterOrDigit(c);
            out.append(keep ? c : '*');
        }
        return out.toString();
    }

    // ---- DTO ----

    public static final class DayData {
        public String dayId = "";
        public long startAt;
        public long endAt;
        public long drawAt;
        public String state = "BETTING";
        public boolean covered;
        public int deathCount;
        public int playerDeaths;
        public int villagerDeaths;
        public int namedMobDeaths;
        public List<BetData> bets = new ArrayList<>();
        public Set<String> victims = new LinkedHashSet<>();
        public ResultData result;
    }

    public static final class BetData {
        public String ownerUuid = "";
        public String ownerName = "";
        public String dataUuid = "";
        public int guess;
        public double value;
        public List<String> items = new ArrayList<>();
        public long at;
    }

    public static final class ResultData {
        public int deathCount;
        public int winningTickets;
        public double potValue;
        public double rakeValue;
        public boolean rollover;
        public List<String> winnerNames = new ArrayList<>();
        public long at;
        public String nextDay = "";
    }

    public static final class StateData {
        public String schemaVersion = SCHEMA;
        public String lastSettledDay = "";
        public List<String> rollover = new ArrayList<>();
        public long lastHeartbeat;
    }

    public static final class FundData {
        public List<String> items = new ArrayList<>();
    }

    public static final class ClaimData {
        public String ownerUuid = "";
        public String ownerName = "";
        public String dataUuid = "";
        public String reason = "";
        public List<String> items = new ArrayList<>();
        public long at;
        public boolean claimed;
    }

    public static final class ClaimList {
        public List<ClaimData> claims = new ArrayList<>();
    }

    private static final class Payee {
        final String ownerUuid;
        final String name;
        final String dataUuid;
        final List<ItemStack> items = new ArrayList<>();
        double value;
        int tickets;

        Payee(String ownerUuid, String name) {
            this.ownerUuid = ownerUuid;
            this.name = name;
            this.dataUuid = ownerUuid;
        }
    }

    public record BetOutcome(boolean ok, String error, String dayId, int guess, int tickets,
                             double paid, double overpay, double pot) {
        static BetOutcome error(String message) {
            return new BetOutcome(false, message, "", 0, 0, 0, 0, 0);
        }

        static BetOutcome ok(String dayId, int guess, int tickets, double paid,
                             double overpay, double pot) {
            return new BetOutcome(true, "", dayId, guess, tickets, paid, overpay, pot);
        }
    }

    public record CollectOutcome(boolean ok, String error, List<ItemStack> items, int claims) {
        static CollectOutcome error(String message) {
            return new CollectOutcome(false, message, List.of(), 0);
        }

        static CollectOutcome ok(List<ItemStack> items, int claims) {
            return new CollectOutcome(true, "", List.copyOf(items), claims);
        }
    }
}
