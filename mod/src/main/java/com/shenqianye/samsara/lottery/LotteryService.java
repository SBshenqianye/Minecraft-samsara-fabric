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
    private static final long QUOTE_TTL_MS = 60_000L;
    private static final int MIN_FUND_SLOTS = 54;
    private static final int MAX_FUND_SLOTS = 5400;

    private static LotteryService instance;

    private MinecraftServer server;
    private Path root;
    private RegistryAccess registries;
    private StateData state = new StateData();
    private List<String> fund = new ArrayList<>();
    private List<ClaimData> claims = new ArrayList<>();
    private final Map<String, DayData> dayCache = new LinkedHashMap<>();
    private final Map<UUID, PendingQuote> pendingQuotes = new LinkedHashMap<>();
    /** 解码失败的物品原文（隔离区）；只在服务器数据里保存，日志只写哈希。 */
    private final List<QuarantineEntry> quarantine = new ArrayList<>();
    private final Set<String> quarantineHashes = new LinkedHashSet<>();
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
        this.root = StoragePaths.get().sharedDataRoot().resolve("lottery");
        try {
            Files.createDirectories(root.resolve("days"));
            Files.createDirectories(root.resolve("audit"));
        } catch (IOException e) {
            Samsara.LOGGER.warn("[lottery] 无法创建乐透数据目录：{}", e.getMessage());
        }
        // 旧世界/旧栈层已结算的轮次补进 shared；不动 fund/state/claims，避免重复计钱。
        LotteryHistoryMigration.merge(StoragePaths.get().worldRoot(), root);
        loadState();
        loadFund();
        loadClaims();
        loadQuarantine();
        GoldPriceService.getInstance().init();
        ItemValueService.setGoldValueProvider(() -> GoldPriceService.getInstance().blockValue());
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
        ensureSeed(todayId());
        GoldPriceService.getInstance().tick();
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

    /**
     * 默认下注流程：只估值，不扣物品。玩家确认后调用 {@link #confirm(ServerPlayer)}。
     */
    public synchronized QuoteOutcome quote(ServerPlayer player, int guess, int tickets, boolean exact) {
        PrepareResult result = prepare(player, guess, tickets, exact);
        if (result.error() != null) {
            return QuoteOutcome.error(result.error());
        }
        Prepared prepared = result.prepared();
        pendingQuotes.put(player.getUUID(), new PendingQuote(prepared, System.currentTimeMillis()));
        return QuoteOutcome.ok(prepared);
    }

    /**
     * 确认最近一次估值；确认前重新校验主手物品，防止报价后换物品/数量。
     */
    public synchronized BetOutcome confirm(ServerPlayer player) {
        if (player == null) {
            return BetOutcome.error("需要玩家身份。");
        }
        PendingQuote pending = pendingQuotes.remove(player.getUUID());
        if (pending == null) {
            return BetOutcome.error("没有待确认的下注报价；请先执行 /samsara lottery bet <猜测> [张数]。");
        }
        if (System.currentTimeMillis() - pending.at() > QUOTE_TTL_MS) {
            return BetOutcome.error("下注报价已过期，请重新估值。");
        }
        Prepared old = pending.prepared();
        PrepareResult now = prepare(player, old.guess(), old.tickets(), old.exact());
        if (now.error() != null) {
            return BetOutcome.error(now.error());
        }
        Prepared prepared = now.prepared();
        if (!prepared.paidEncoded().equals(old.paidEncoded())) {
            return BetOutcome.error("主手物品或数量已变化，请重新估值后再确认。");
        }
        return consume(player, prepared);
    }

    /** 快速通道：跳过估值，直接按当前主手扣物品。 */
    public synchronized BetOutcome betNow(ServerPlayer player, int guess, int tickets, boolean exact) {
        PrepareResult result = prepare(player, guess, tickets, exact);
        if (result.error() != null) {
            return BetOutcome.error(result.error());
        }
        return consume(player, result.prepared());
    }

    public synchronized boolean cancelQuote(ServerPlayer player) {
        return player != null && pendingQuotes.remove(player.getUUID()) != null;
    }

    private PrepareResult prepare(ServerPlayer player, int guess, int tickets, boolean exact) {
        LotteryConfig config = LotteryConfig.getInstance();
        if (!config.enabled()) {
            return PrepareResult.error("死人乐透未启用。");
        }
        if (player == null) {
            return PrepareResult.error("需要玩家身份。");
        }
        if (guess < 0 || guess > 4096) {
            return PrepareResult.error("猜测数字应在 0~4096 之间。");
        }
        if (tickets < 1 || tickets > config.maxTicketsPerPlayer()) {
            return PrepareResult.error("单次可买 1~" + config.maxTicketsPerPlayer() + " 张。");
        }
        String dayId = bettingDayId();
        DayData day = loadOrCreateDay(dayId);
        int mine = 0;
        for (BetData bet : day.bets) {
            if (bet.ownerUuid.equals(player.getUUID().toString())) {
                mine += ticketsOf(bet);
            }
        }
        if (mine + tickets > config.maxTicketsPerPlayer()) {
            return PrepareResult.error("本轮你最多 " + config.maxTicketsPerPlayer()
                    + " 张，已买 " + mine + " 张。");
        }
        int roundTickets = totalTickets(day);
        if (roundTickets + tickets > config.maxTicketsPerRound()) {
            return PrepareResult.error("本轮票数已满（" + config.maxTicketsPerRound() + " 张）。");
        }

        int slot = player.getInventory().getSelectedSlot();
        ItemStack hand = player.getInventory().getItem(slot);
        double unit = ItemValueService.getInstance().unitValue(hand);
        if (unit <= 0.0) {
            return PrepareResult.error("主手物品没有价值；请手持可估值的物品（铁锭/钻石等）。");
        }
        double required = config.ticketPrice() * tickets;
        double available = unit * hand.getCount();
        int byPlayer = config.maxTicketsPerPlayer() - mine;
        int byRound = config.maxTicketsPerRound() - roundTickets;
        int affordable = affordableTickets(available, config.ticketPrice(), byPlayer, byRound);
        if (available + 0.0001 < required) {
            return PrepareResult.error("主手价值不足：需要 " + ItemValueService.getInstance().format(required)
                    + " VP，当前 " + ItemValueService.getInstance().format(available)
                    + " VP；最多可买 " + affordable + " 张。");
        }
        if (tickets > affordable) {
            return PrepareResult.error("当前最多可买 " + affordable + " 张（受物品价值/每人/单轮上限限制），本次请求 "
                    + tickets + " 张。");
        }
        if (exact && available > required + 0.0001) {
            return PrepareResult.error("--exact 要求不超额：需要 " + ItemValueService.getInstance().format(required)
                    + " VP，当前 " + ItemValueService.getInstance().format(available) + " VP。");
        }
        double potBefore = potValue(day) + valueOfEncoded(state.rollover);
        if (potBefore + required > config.maxPotValue()) {
            return PrepareResult.error("本轮奖池已达上限 " + config.maxPotValue() + " VP。");
        }

        int take = (int) Math.ceil(required / unit);
        take = Math.max(1, Math.min(take, hand.getCount()));
        double actual = unit * take;
        if (actual + 0.0001 < required) {
            return PrepareResult.error("主手物品不足以支付票价。");
        }
        ItemStack paid = hand.copyWithCount(take);
        String paidEncoded = LotteryItemCodec.encode(paid, registries);
        if (paidEncoded.isBlank()) {
            return PrepareResult.error("主手物品快照失败，请换一件物品再试。");
        }
        double overpay = Math.max(0.0, actual - required);
        return PrepareResult.ok(new Prepared(dayId, guess, tickets, exact, slot,
                paidEncoded, hand.getHoverName().getString(), hand.getCount(), unit,
                available, required, take, actual, overpay, potBefore + actual, affordable));
    }

    private BetOutcome consume(ServerPlayer player, Prepared prepared) {
        DayData day = loadOrCreateDay(prepared.dayId());
        ItemStack hand = player.getInventory().getItem(prepared.slot());
        if (hand.isEmpty() || hand.getCount() < prepared.take()) {
            return BetOutcome.error("主手物品已变化，请重新估值。");
        }
        ItemStack paid = hand.copyWithCount(prepared.take());
        if (!prepared.paidEncoded().equals(LotteryItemCodec.encode(paid, registries))) {
            return BetOutcome.error("主手物品已变化，请重新估值。");
        }
        hand.shrink(prepared.take());
        player.getInventory().setItem(prepared.slot(), hand.isEmpty() ? ItemStack.EMPTY : hand);
        player.containerMenu.broadcastChanges();

        BetData bet = new BetData();
        bet.ownerUuid = player.getUUID().toString();
        bet.ownerName = player.getGameProfile().name();
        bet.dataUuid = player.getUUID().toString();
        bet.guess = prepared.guess();
        bet.tickets = prepared.tickets();
        bet.value = prepared.actual();
        bet.items = LotteryItemCodec.encodeAll(List.of(paid), registries);
        bet.at = System.currentTimeMillis();
        day.bets.add(bet);
        if (!saveDay(day)) {
            // 落盘失败：撤销内存里的注单并把物品退回，不能出现“下注成功但重启就没了”。
            day.bets.remove(bet);
            ItemStack current = player.getInventory().getItem(prepared.slot());
            if (current.isEmpty()) {
                player.getInventory().setItem(prepared.slot(), paid.copy());
            } else {
                current.grow(prepared.take());
                player.getInventory().setItem(prepared.slot(), current);
            }
            player.containerMenu.broadcastChanges();
            Samsara.LOGGER.error("[lottery] 下注保存失败，已退回 {} 的物品", player.getScoreboardName());
            return BetOutcome.error("下注保存失败（磁盘/权限），物品已退回，请稍后再试。");
        }

        return BetOutcome.ok(prepared.dayId(), prepared.guess(), prepared.tickets(),
                prepared.actual(), prepared.overpay(), prepared.pot(), prepared.paidEncoded());
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
            return CollectOutcome.ok(List.of(), 0, false);
        }
        List<ItemStack> given = new ArrayList<>();
        int completed = 0;
        boolean remaining = false;
        for (ClaimData claim : mine) {
            List<ItemStack> items = decodeItems(claim.items, "待领取");
            Delivery delivery = addToInventory(player, items);
            given.addAll(delivery.given());
            if (delivery.remainder().isEmpty()) {
                claim.claimed = true;
                completed++;
            } else {
                // 背包放不下的部分留在待领取，不丢到地上（避免岩浆/虚空等危险位置）。
                claim.items = LotteryItemCodec.encodeAll(delivery.remainder(), registries);
                remaining = true;
            }
        }
        if (!saveClaims()) {
            // 物品已经发出；这里只能明确告诉玩家记录未落盘，避免他们以为仍是待领取。
            Samsara.LOGGER.error("[lottery] 领取记录保存失败：{}", player.getScoreboardName());
            return CollectOutcome.error("物品已放入背包，但领取记录保存失败（磁盘/权限）；"
                    + "重启后可能仍显示待领取，请联系管理员核对。");
        }
        return CollectOutcome.ok(given, completed, remaining);
    }

    // ---- 状态 / 历史 ----

    public synchronized LotteryViews.Status statusModel(ServerPlayer player) {
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        DayData last = lastSettled();
        return new LotteryViews.Status(betting, todayId(), drawLabel(betting), cutoffLabel(betting),
                potValue(day) + valueOfEncoded(state.rollover), totalTickets(day),
                distinctPlayers(day), myTickets(day, player),
                last == null ? null : resultModel(last),
                LotteryConfig.getInstance().enabled());
    }

    public synchronized List<LotteryViews.Result> historyModel(int page, int pageSize) {
        List<String> ids = listSettledDayIds();
        ids.sort((a, b) -> compareDayId(b, a));
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(ids.size(), from + pageSize);
        List<LotteryViews.Result> out = new ArrayList<>();
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
        if (!settle(dayId, false, true)) {
            return "开奖失败：数据未能写入磁盘（磁盘/权限），请检查日志后重试；奖池状态未变更。";
        }
        return "已开奖：" + dayId;
    }

    public synchronized String cancelNow() {
        String dayId = todayId();
        DayData day = loadOrCreateDay(dayId);
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            return "该结算日已经结束：" + dayId;
        }
        boolean refunded = refundAll(day, "管理员取消");
        returnSeedToFund(day);
        day.state = "CANCELLED";
        day.result = new ResultData();
        day.result.deathCount = day.deathCount;
        day.result.winningTickets = 0;
        day.result.potValue = 0;
        day.result.rakeValue = 0;
        day.result.rollover = false;
        day.result.refunded = true;
        day.result.refundReason = "管理员取消";
        day.result.nextDay = bettingDayId();
        day.result.at = System.currentTimeMillis();
        boolean saved = saveDay(day);
        if (!refunded || !saved) {
            return "已退款并取消，但部分数据未能写入磁盘（磁盘/权限）；请检查日志并核对待领取。";
        }
        return "已取消并退款：" + dayId;
    }

    public synchronized LotteryViews.Fund fundModel() {
        List<ItemStack> items = LotteryItemCodec.decodeAll(fund, registries);
        return new LotteryViews.Fund(ItemValueService.getInstance().valueOf(items), itemModels(items));
    }

    /** 基金仓库：至少 54 格，按 Web 提交的槽位数自动扩容，无硬上限。 */
    public synchronized LotteryViews.FundInventory fundInventory() {
        List<String> slots = new ArrayList<>(fund);
        while (slots.size() < MIN_FUND_SLOTS) slots.add("");
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            String encoded = slots.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack = LotteryItemCodec.decode(encoded, registries);
            if (stack.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>(
                    com.shenqianye.samsara.item.ItemStackModel.compact(stack, encoded));
            row.put("value", ItemValueService.getInstance().valueOf(stack));
            row.put("slot", i);
            items.add(row);
        }
        return new LotteryViews.FundInventory(true, slots.size(), MIN_FUND_SLOTS, slots, items,
                ItemValueService.getInstance().valueOf(LotteryItemCodec.decodeAll(slots, registries)));
    }

    /** 写回基金仓库；槽位数不设上限，提交更短的数组即可缩容回最小 54 格。 */
    public synchronized LotteryViews.Outcome writeFundInventory(List<String> slots) {
        if (slots == null) {
            return new LotteryViews.WriteError(false, 400, "缺少 slots");
        }
        List<String> normalized = new ArrayList<>(slots);
        while (normalized.size() < MIN_FUND_SLOTS) normalized.add("");
        for (int i = 0; i < normalized.size(); i++) {
            String encoded = normalized.get(i);
            if (encoded == null || encoded.isBlank()) continue;
            ItemStack stack = LotteryItemCodec.decode(encoded, registries);
            if (stack.isEmpty()) {
                return new LotteryViews.WriteError(false, 400, "第 " + i + " 格物品编码无效");
            }
            if (stack.getCount() <= 0) {
                return new LotteryViews.WriteError(false, 400, "第 " + i + " 格数量无效");
            }
        }
        fund = normalized;
        saveFund();
        return fundInventory();
    }

    /** 把实物列表转成 Web 通用物品模型，供基金 / 奖池共用。 */
    private List<Map<String, Object>> itemModels(List<ItemStack> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            String itemId = ItemValueService.getInstance().idOf(stack.getItem());
            item.put("id", itemId);
            item.put("item", itemId);
            item.put("name", stack.getHoverName().getString());
            item.put("icon", iconName(itemId));
            item.put("count", stack.getCount());
            item.put("value", ItemValueService.getInstance().valueOf(stack));
            ItemValueService.getInstance().applyLang(item, itemId);
            list.add(item);
        }
        return list;
    }

    /** 公开端物品模型：去掉内部序号，只保留展示字段。 */
    private List<Map<String, Object>> publicItemModels(List<Map<String, Object>> models) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : List.of("id", "item", "name", "zh", "icon", "count", "value")) {
                if (raw.containsKey(key)) item.put(key, raw.get(key));
            }
            out.add(item);
        }
        return out;
    }

    /** 某一期自己的下注物品（含基金种子，不含滚存），用于“下注轮已入池”。 */
    private List<ItemStack> dayPotItems(DayData day) {
        List<ItemStack> pot = new ArrayList<>();
        if (day.seed != null) {
            pot.addAll(decodeItems(day.seed, "下注轮基金种子"));
        }
        for (BetData bet : day.bets) {
            pot.addAll(decodeItems(bet.items, "下注物品"));
        }
        return pot;
    }

    /** 当前统计轮实际待开奖物品：本期下注 + 历史滚存。 */
    private List<ItemStack> activePotItems(DayData counting) {
        List<ItemStack> pot = dayPotItems(counting);
        if (state.rollover != null) {
            pot.addAll(decodeItems(state.rollover, "奖池滚存"));
        }
        return pot;
    }

    /** 管理端待领取列表：退款/中奖因背包满或离线而暂存的实物。 */
    public synchronized List<LotteryViews.Claim> claimModels() {
        List<LotteryViews.Claim> out = new ArrayList<>();
        for (ClaimData claim : claims) {
            if (claim.claimed) {
                continue;
            }
            out.add(new LotteryViews.Claim(claim.ownerName, claim.dataUuid, claim.reason, claim.at,
                    itemModels(LotteryItemCodec.decodeAll(claim.items, registries))));
        }
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    /** 保险理赔前检查：该物品是否仍由乐透系统持有（下注/滚存/基金/待领取）。 */
    public synchronized boolean holdsEncodedItem(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        if (state.rollover != null && state.rollover.contains(encoded)) {
            return true;
        }
        if (fund.contains(encoded)) {
            return true;
        }
        for (ClaimData claim : claims) {
            if (!claim.claimed && claim.items != null && claim.items.contains(encoded)) {
                return true;
            }
        }
        if (server == null) {
            return false;
        }
        for (String dayId : List.of(bettingDayId(), todayId())) {
            DayData day = loadDay(dayId);
            if (day == null) {
                continue;
            }
            if (day.seed != null && day.seed.contains(encoded)) {
                return true;
            }
            if (day.bets == null) {
                continue;
            }
            for (BetData bet : day.bets) {
                if (bet.items != null && bet.items.contains(encoded)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized String withdrawFund(ServerPlayer player, int index) {
        if (player == null) {
            return "需要玩家身份；控制台不能领取基金物品。";
        }
        List<ItemStack> items = LotteryItemCodec.decodeAll(fund, registries);
        if (index < 0 || index >= items.size()) {
            return "基金序号无效：" + index;
        }
        ItemStack stack = items.get(index);
        Delivery delivery = addToInventory(player, List.of(stack));
        if (delivery.given().isEmpty()) {
            return "背包空间不足，物品仍在基金。";
        }
        ItemStack given = delivery.given().get(0);
        if (delivery.remainder().isEmpty()) {
            items.remove(index);
        } else {
            items.set(index, delivery.remainder().get(0));
        }
        fund = LotteryItemCodec.encodeAll(items, registries);
        saveFund();
        return "已从基金取出：" + given.getCount() + " × " + given.getHoverName().getString()
                + (delivery.remainder().isEmpty() ? ""
                : "；背包空间不足，" + delivery.remainder().get(0).getCount() + " 个仍在基金");
    }

    private LotteryViews.Gold goldModel() {
        LotteryConfig config = LotteryConfig.getInstance();
        ItemValueService values = ItemValueService.getInstance();
        return new LotteryViews.Gold(config.goldSyncEnabled(),
                GoldPriceService.getInstance().currentUsdPerOunce(),
                GoldPriceService.getInstance().updatedAt(),
                GoldPriceService.getInstance().source(),
                config.goldBaseBlockValue(),
                config.goldReferenceUsdPerOunce(),
                values.unitValue("minecraft:gold_block"),
                values.unitValue("minecraft:gold_ingot"),
                values.unitValue("minecraft:gold_nugget"));
    }

    public synchronized LotteryViews.Admin adminModel() {
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        DayData counting = loadOrCreateDay(todayId());
        double rolloverValue = valueOfEncoded(state.rollover == null ? List.of() : state.rollover);
        return new LotteryViews.Admin(
                LotteryConfig.getInstance().enabled(),
                dayMode(),
                dayModel(day),
                dayModel(counting),
                rolloverValue,
                itemModels(LotteryItemCodec.decodeAll(
                        state.rollover == null ? List.of() : state.rollover, registries)),
                potValue(counting) + rolloverValue,
                itemModels(activePotItems(counting)),
                potValue(day),
                itemModels(dayPotItems(day)),
                claimModels(),
                goldModel(),
                fundModel(),
                historyModel(1, 10),
                LotteryConfig.getInstance().configPath(),
                null);
    }

    public synchronized LotteryViews.PublicModel publicModel() {
        LotteryConfig config = LotteryConfig.getInstance();
        boolean visible = config.publicVisible();
        boolean enabled = config.enabled() && visible;
        if (!visible) {
            return new LotteryViews.PublicModel(true, "user", enabled, null, null, null, null, null,
                    null, null);
        }
        String betting = bettingDayId();
        DayData day = loadOrCreateDay(betting);
        DayData counting = loadOrCreateDay(todayId());
        double rolloverValue = valueOfEncoded(state.rollover == null ? List.of() : state.rollover);
        LotteryViews.Round round = new LotteryViews.Round(betting, drawLabel(betting),
                cutoffLabel(betting), potValue(day),
                valueOfEncoded(day.seed == null ? List.of() : day.seed),
                potValue(day) + rolloverValue, totalTickets(day), distinctPlayers(day),
                publicItemModels(itemModels(dayPotItems(day))));
        LotteryViews.Active active = new LotteryViews.Active(counting.dayId,
                drawLabel(counting.dayId), cutoffLabel(counting.dayId),
                valueOfEncoded(counting.seed == null ? List.of() : counting.seed),
                potValue(counting) + rolloverValue, totalTickets(counting),
                distinctPlayers(counting), publicItemModels(itemModels(activePotItems(counting))));
        DayData last = lastSettled();
        LotteryViews.Fund fundModel = fundModel();
        List<Map<String, Object>> publicItems = new ArrayList<>();
        for (Map<String, Object> raw : fundModel.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : List.of("id", "item", "name", "zh", "icon", "count", "value")) {
                if (raw.containsKey(key)) item.put(key, raw.get(key));
            }
            publicItems.add(item);
        }
        LotteryViews.PublicFund fund = new LotteryViews.PublicFund(fundModel.value(),
                publicItems.size(), publicItems);
        LotteryViews.PublicGold gold = new LotteryViews.PublicGold(config.goldSyncEnabled(),
                GoldPriceService.getInstance().currentUsdPerOunce(),
                GoldPriceService.getInstance().updatedAt(),
                ItemValueService.getInstance().unitValue("minecraft:gold_block"));
        return new LotteryViews.PublicModel(true, "user", enabled, round, active, rolloverValue,
                last == null ? null : publicResult(last), fund, gold, null);
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
            if (!settle(candidate, missed, false)) {
                Samsara.LOGGER.error("[lottery] 结算 {} 失败，下一 tick 重试", candidate);
                return;
            }
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

    /** 每轮开始时从服务器基金注入初始奖池；每轮只处理一次。 */
    private void ensureSeed(String dayId) {
        LotteryConfig config = LotteryConfig.getInstance();
        DayData day = loadOrCreateDay(dayId);
        if (day.seeded) {
            return;
        }
        day.seeded = true;
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            saveDay(day);
            return;
        }
        if (!config.seedEnabled()) {
            saveDay(day);
            return;
        }
        double fundValue = valueOfEncoded(fund == null ? List.of() : fund);
        double target = seedTarget(fundValue, config.seedPercent(),
                config.seedMaxValue(), config.seedMinFundValue());
        if (target <= 0.0001) {
            saveDay(day);
            return;
        }
        List<ItemStack> seed = takeFromFund(target);
        day.seed = LotteryItemCodec.encodeAll(seed, registries);
        saveDay(day);
    }

    /**
     * 从基金取出不超过 target 价值的物品。单件高价值物品不会被强行抽走。
     */
    private List<ItemStack> takeFromFund(double target) {
        List<ItemStack> items = LotteryItemCodec.decodeAll(fund == null ? List.of() : fund, registries);
        items.sort(Comparator.comparingDouble(
                stack -> ItemValueService.getInstance().unitValue(stack)));
        List<ItemStack> taken = new ArrayList<>();
        double takenValue = 0.0;
        for (int i = 0; i < items.size() && takenValue < target; ) {
            ItemStack stack = items.get(i);
            double unit = ItemValueService.getInstance().unitValue(stack);
            if (unit <= 0.0 || unit > target - takenValue + 0.0001) {
                i++;
                continue;
            }
            int need = (int) Math.floor((target - takenValue) / unit);
            int take = Math.max(1, Math.min(stack.getCount(), need));
            taken.add(stack.copyWithCount(take));
            stack.shrink(take);
            takenValue += unit * take;
            if (stack.isEmpty()) {
                items.remove(i);
            } else {
                i++;
            }
        }
        fund = LotteryItemCodec.encodeAll(items, registries);
        saveFund();
        return taken;
    }

    /** 取消/流局时把未使用的基金种子退回基金。 */
    private boolean returnSeedToFund(DayData day) {
        if (day.seed == null || day.seed.isEmpty()) {
            return true;
        }
        fund.addAll(day.seed);
        day.seed = new ArrayList<>();
        return saveFund();
    }

    private boolean drawReached(String dayId) {
        DayData day = loadOrCreateDay(dayId);
        if (LotteryConfig.getInstance().realDay()) {
            return System.currentTimeMillis() >= day.drawAt;
        }
        return gameTime() >= day.drawAt;
    }

    private boolean settle(String dayId, boolean missed, boolean force) {
        DayData day = loadDay(dayId);
        if (day == null) {
            day = loadOrCreateDay(dayId);
        }
        if ("SETTLED".equals(day.state) || "CANCELLED".equals(day.state)) {
            return true;
        }
        if (!force && !drawReached(dayId)) {
            return true;
        }
        boolean ok = true;
        if (!day.covered && !day.bets.isEmpty()) {
            ok &= refundAll(day, "统计不完整（服务器离线）");
            ok &= returnSeedToFund(day);
            day.result = new ResultData();
            day.result.refunded = true;
            day.result.refundReason = "统计不完整（服务器离线）";
        } else if (day.bets.isEmpty()) {
            // 没有票，只记录死亡数；本轮未用掉的种子退回基金
            ok &= returnSeedToFund(day);
        } else {
            ok &= settleWithBets(day);
        }
        day.state = "SETTLED";
        day.result = day.result == null ? new ResultData() : day.result;
        day.result.deathCount = day.deathCount;
        day.result.at = System.currentTimeMillis();
        day.result.nextDay = nextDayId(day.dayId);
        if (day.result.winnerNames == null) {
            day.result.winnerNames = new ArrayList<>();
        }
        ok &= saveDay(day);
        if (!ok) {
            Samsara.LOGGER.error("[lottery] 开奖数据未能完整写入磁盘：{}（下一 tick 会重试）", dayId);
            return false;
        }
        announce(day);
        state.lastSettledDay = maxDay(state.lastSettledDay, dayId);
        return saveState();
    }

    private boolean settleWithBets(DayData day) {
        boolean ok = true;
        potTaken.clear();
        List<ItemStack> pot = new ArrayList<>();
        if (day.seed != null) {
            pot.addAll(decodeItems(day.seed, "开奖基金种子"));
        }
        for (BetData bet : day.bets) {
            pot.addAll(decodeItems(bet.items, "开奖下注物品"));
        }
        pot.addAll(decodeItems(state.rollover, "开奖滚存"));
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
        int winningTickets = 0;
        for (BetData bet : winners) {
            winningTickets += ticketsOf(bet);
        }
        result.winningTickets = winningTickets;
        result.winnerNames = new ArrayList<>();
        result.at = System.currentTimeMillis();
        result.nextDay = nextDayId(day.dayId);
        result.rollover = false;

        if (winners.isEmpty()) {
            state.rollover = LotteryItemCodec.encodeAll(pot, registries);
            result.rollover = true;
            day.result = result;
            day.seed = new ArrayList<>();
            return saveState();
        }

        double rake = result.potValue * LotteryConfig.getInstance().rakePercent() / 100.0;
        double actualRake = takeValue(pot, rake);
        result.rakeValue = actualRake;
        if ("fund".equalsIgnoreCase(LotteryConfig.getInstance().rakeDestination())) {
            fund.addAll(LotteryItemCodec.encodeAll(potTaken, registries));
            potTaken.clear();
            ok &= saveFund();
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
        // 同一玩家可能持有多张中奖票；合并后只发一次消息/待领取。
        Map<String, Payee> merged = new LinkedHashMap<>();
        for (Payee payee : payees) {
            Payee group = merged.computeIfAbsent(payee.ownerUuid,
                    key -> new Payee(payee.ownerUuid, payee.name));
            group.items.addAll(payee.items);
            group.value += payee.value;
            group.tickets += payee.tickets;
        }
        for (Payee payee : merged.values()) {
            result.winnerNames.add(payee.name);
            deliver(payee, result.deathCount);
        }
        // 未分配的余数滚存
        if (!pot.isEmpty()) {
            state.rollover = LotteryItemCodec.encodeAll(pot, registries);
        }
        day.result = result;
        day.seed = new ArrayList<>();
        ok &= saveState();
        ok &= saveClaims();
        return ok;
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
                payee.tickets += ticketsOf(bet);
            }
            return new ArrayList<>(map.values());
        }
        List<Payee> out = new ArrayList<>();
        for (BetData bet : winners) {
            int tickets = ticketsOf(bet);
            for (int i = 0; i < tickets; i++) {
                Payee payee = new Payee(bet.ownerUuid, bet.ownerName);
                payee.tickets = 1;
                out.add(payee);
            }
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
        List<ItemStack> remaining = payee.items;
        if (online != null) {
            Delivery delivery = addToInventory(online, payee.items);
            remaining = delivery.remainder();
            String claimLine = remaining.isEmpty()
                    ? "奖金已放入背包。"
                    : "背包空间不足，剩余 " + summarize(remaining)
                    + " 已存入待领取；腾出空间后执行 /samsara lottery collect 领取。";
            online.sendSystemMessage(OutputRenderer.render("lottery.win",
                    Map.of("deathCount", String.valueOf(deathCount),
                            "guess", "?",
                            "shareLine", summarize(payee.items),
                            "claimLine", claimLine), null, null));
        }
        if (!remaining.isEmpty()) {
            addClaim(payee.ownerUuid, payee.name, payee.dataUuid, "中奖", remaining);
        }
    }

    private boolean refundAll(DayData day, String reason) {
        for (BetData bet : day.bets) {
            List<ItemStack> items = decodeItems(bet.items, "退款");
            ServerPlayer online = online(bet.ownerUuid);
            List<ItemStack> remaining = items;
            if (online != null) {
                Delivery delivery = addToInventory(online, items);
                remaining = delivery.remainder();
                if (!remaining.isEmpty()) {
                    online.sendSystemMessage(OutputRenderer.render("lottery.reply", Map.of(
                            "message", "背包空间不足，剩余 " + summarize(remaining)
                            + " 已存入待领取；腾出空间后执行 /samsara lottery collect 领取。"),
                            null, null));
                }
            }
            if (!remaining.isEmpty()) {
                addClaim(bet.ownerUuid, bet.ownerName, bet.dataUuid, reason, remaining);
            }
        }
        day.bets.clear();
        return saveClaims();
    }

    private void announce(DayData day) {
        if (server == null || day.result == null) {
            return;
        }
        String cause = day.playerDeaths + " 名玩家、" + day.villagerDeaths + " 名村民、"
                + day.namedMobDeaths + " 只命名怪";
        String winnerLine;
        if (day.result.refunded) {
            String reason = day.result.refundReason == null || day.result.refundReason.isBlank()
                    ? "本轮取消" : day.result.refundReason;
            winnerLine = "本轮退款（" + reason + "）：票款按原物退回，奖池不滚存。";
        } else if (day.result.winningTickets == 0) {
            winnerLine = "无人猜中，奖池滚入下一轮。";
        } else {
            winnerLine = "中奖 " + day.result.winningTickets + " 张票。";
        }
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

    private boolean saveDay(DayData day) {
        if (root == null || day == null) {
            return false;
        }
        dayCache.put(day.dayId, day);
        return write(root.resolve("days").resolve(day.dayId + ".json"), GSON.toJson(day));
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

    private boolean saveState() {
        if (root == null) {
            return false;
        }
        state.schemaVersion = SCHEMA;
        return write(root.resolve("state.json"), GSON.toJson(state));
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

    private boolean saveFund() {
        if (root == null) {
            return false;
        }
        FundData data = new FundData();
        data.items = new ArrayList<>(fund);
        return write(root.resolve("fund.json"), GSON.toJson(data));
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

    private boolean saveClaims() {
        if (root == null) {
            return false;
        }
        ClaimList data = new ClaimList();
        data.claims = new ArrayList<>(claims);
        return write(root.resolve("claims.json"), GSON.toJson(data));
    }

    private void loadQuarantine() {
        quarantine.clear();
        quarantineHashes.clear();
        if (root == null) {
            return;
        }
        Path file = root.resolve("quarantine.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            QuarantineData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    QuarantineData.class);
            if (data != null && data.entries != null) {
                for (QuarantineEntry entry : data.entries) {
                    if (entry == null || entry.encoded == null || entry.encoded.isBlank()) {
                        continue;
                    }
                    if (entry.hash == null || entry.hash.isBlank()) {
                        entry.hash = LotteryItemCodec.hash(entry.encoded);
                    }
                    quarantine.add(entry);
                    quarantineHashes.add(entry.hash);
                }
            }
        } catch (RuntimeException | IOException e) {
            Samsara.LOGGER.warn("[lottery] 读取隔离区失败：{}", e.getMessage());
        }
    }

    /**
     * 解码一组物品；失败的原文进入隔离区并写 WARN（日志只含哈希）。
     *
     * <p>以前解码失败直接 `orElse(EMPTY)`，物品会从奖池/待领取里静默消失。
     */
    private List<ItemStack> decodeItems(List<String> encoded, String source) {
        List<ItemStack> out = new ArrayList<>();
        if (encoded == null) {
            return out;
        }
        for (String value : encoded) {
            ItemStack stack = LotteryItemCodec.decode(value, registries);
            if (!stack.isEmpty()) {
                out.add(stack);
            } else if (value != null && !value.isBlank()) {
                quarantineItem(value, source);
            }
        }
        return out;
    }

    private void quarantineItem(String encoded, String source) {
        String hash = LotteryItemCodec.hash(encoded);
        if (!quarantineHashes.add(hash)) {
            return;
        }
        Samsara.LOGGER.warn("[lottery] 物品解码失败，已放入隔离区：来源={} hash={}", source, hash);
        if (quarantine.size() >= 1000) {
            QuarantineEntry removed = quarantine.remove(0);
            quarantineHashes.remove(removed.hash);
        }
        QuarantineEntry entry = new QuarantineEntry();
        entry.at = System.currentTimeMillis();
        entry.source = source == null ? "" : source;
        entry.hash = hash;
        entry.encoded = encoded;
        quarantine.add(entry);
        QuarantineData data = new QuarantineData();
        data.entries = new ArrayList<>(quarantine);
        write(root == null ? null : root.resolve("quarantine.json"), GSON.toJson(data));
    }

    private boolean write(Path file, String content) {
        if (file == null) {
            return false;
        }
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
            return true;
        } catch (IOException e) {
            Samsara.LOGGER.warn("[lottery] 写入 {} 失败：{}", file, e.getMessage());
            return false;
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

    /**
     * 只放进背包；放不下的部分原样返回，绝不丢到地上。
     * 这样退款/中奖即使发生在岩浆、虚空或背包满时也不会丢失物品。
     */
    private Delivery addToInventory(ServerPlayer player, List<ItemStack> items) {
        List<ItemStack> given = new ArrayList<>();
        List<ItemStack> remainder = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack copy = stack.copy();
            int before = copy.getCount();
            player.getInventory().add(copy);
            int left = copy.getCount();
            if (left < before) {
                given.add(stack.copyWithCount(before - left));
            }
            if (left > 0) {
                remainder.add(copy.copyWithCount(left));
            }
        }
        player.containerMenu.broadcastChanges();
        return new Delivery(given, remainder);
    }

    private void addClaim(String ownerUuid, String ownerName, String dataUuid,
                          String reason, List<ItemStack> items) {
        ClaimData claim = new ClaimData();
        claim.ownerUuid = ownerUuid;
        claim.ownerName = ownerName;
        claim.dataUuid = dataUuid;
        claim.reason = reason;
        claim.items = LotteryItemCodec.encodeAll(items, registries);
        claim.at = System.currentTimeMillis();
        claim.claimed = false;
        claims.add(claim);
    }

    private record Delivery(List<ItemStack> given, List<ItemStack> remainder) {
    }

    private double potValue(DayData day) {
        double total = valueOfEncoded(day.seed == null ? List.of() : day.seed);
        for (BetData bet : day.bets) {
            total += bet.value;
        }
        return total;
    }

    static int ticketsOf(BetData bet) {
        return bet == null || bet.tickets < 1 ? 1 : bet.tickets;
    }

    /** 当前主手价值最多够买几张票，同时受每人/单轮剩余票数限制。 */
    static int affordableTickets(double available, double unitPrice, int playerRemaining,
                                 int roundRemaining) {
        if (unitPrice <= 0.0 || available <= 0.0) {
            return 0;
        }
        int byValue = (int) Math.floor((available + 0.0001) / unitPrice);
        return Math.max(0, Math.min(byValue, Math.min(playerRemaining, roundRemaining)));
    }

    /** 本轮从基金抽取的种子价值。 */
    static double seedTarget(double fundValue, double percent, double maxValue,
                             double minFundValue) {
        if (fundValue <= 0.0 || fundValue + 0.0001 < minFundValue) {
            return 0.0;
        }
        double target = fundValue * Math.max(0.0, Math.min(100.0, percent)) / 100.0;
        if (maxValue > 0.0) {
            target = Math.min(target, maxValue);
        }
        return Math.max(0.0, target);
    }

    static int totalTickets(DayData day) {
        int total = 0;
        for (BetData bet : day.bets) {
            total += ticketsOf(bet);
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
                int tickets = ticketsOf(bet);
                parts.add(bet.guess + " 号" + (tickets > 1 ? " × " + tickets : ""));
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

    private LotteryViews.Day dayModel(DayData day) {
        List<LotteryViews.Bet> bets = new ArrayList<>();
        for (BetData bet : day.bets) {
            bets.add(new LotteryViews.Bet(bet.ownerName, bet.guess, ticketsOf(bet), bet.value));
        }
        return new LotteryViews.Day(day.dayId, day.state, day.covered, day.deathCount,
                day.playerDeaths, day.villagerDeaths, day.namedMobDeaths, totalTickets(day),
                day.seeded,
                valueOfEncoded(day.seed == null ? List.of() : day.seed),
                itemModels(day.seed == null ? List.of()
                        : LotteryItemCodec.decodeAll(day.seed, registries)),
                potValue(day),
                potValue(day) + valueOfEncoded(state.rollover),
                itemModels(dayPotItems(day)),
                drawLabel(day.dayId), cutoffLabel(day.dayId), bets);
    }

    private LotteryViews.Result resultModel(DayData day) {
        return new LotteryViews.Result(day.dayId,
                day.result == null ? day.deathCount : day.result.deathCount,
                day.result == null ? 0 : day.result.winningTickets,
                day.result == null ? 0 : day.result.potValue,
                day.result == null ? 0 : day.result.rakeValue,
                day.result != null && day.result.rollover,
                day.result != null && day.result.refunded,
                day.result == null ? "" : day.result.refundReason,
                day.result == null ? List.of() : day.result.winnerNames,
                day.result == null ? 0 : day.result.at);
    }

    private LotteryViews.PublicResult publicResult(DayData day) {
        List<String> masked = new ArrayList<>();
        if (day.result != null && day.result.winnerNames != null) {
            for (String name : day.result.winnerNames) {
                masked.add(mask(name));
            }
        }
        return new LotteryViews.PublicResult(day.dayId,
                day.result == null ? day.deathCount : day.result.deathCount,
                day.result == null ? 0 : day.result.winningTickets,
                day.result == null ? 0 : day.result.potValue,
                day.result != null && day.result.rollover,
                day.result != null && day.result.refunded,
                masked);
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
        /** 本轮从服务器基金注入的初始奖池物品。 */
        public List<String> seed = new ArrayList<>();
        /** 是否已经处理过种子注入（基金当时为空也不会重复尝试）。 */
        public boolean seeded;
        public ResultData result;
    }

    public static final class BetData {
        public String ownerUuid = "";
        public String ownerName = "";
        public String dataUuid = "";
        public int guess;
        /** 本注包含的票数；旧数据没有该字段时按 1 张处理。 */
        public int tickets = 1;
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
        /** 本轮改为退款：奖池不滚存，票款按原物退回。 */
        public boolean refunded;
        public String refundReason = "";
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

    public static final class QuarantineData {
        public List<QuarantineEntry> entries = new ArrayList<>();
    }

    /** 解码失败的物品原文；`encoded` 只留在服务器数据目录，不写日志。 */
    public static final class QuarantineEntry {
        public long at;
        public String source = "";
        public String hash = "";
        public String encoded = "";
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

    public record QuoteOutcome(boolean ok, String error, String dayId, int guess, int tickets,
                               int affordable, String itemName, int handCount, double unit,
                               double available, double required, int take, double actual,
                               double overpay, double pot) {
        static QuoteOutcome error(String message) {
            return new QuoteOutcome(false, message, "", 0, 0, 0, "", 0, 0,
                    0, 0, 0, 0, 0, 0);
        }

        static QuoteOutcome ok(Prepared prepared) {
            return new QuoteOutcome(true, "", prepared.dayId(), prepared.guess(),
                    prepared.tickets(), prepared.affordable(), prepared.itemName(),
                    prepared.handCount(), prepared.unit(), prepared.available(),
                    prepared.required(), prepared.take(), prepared.actual(),
                    prepared.overpay(), prepared.pot());
        }
    }

    private record Prepared(String dayId, int guess, int tickets, boolean exact, int slot,
                            String paidEncoded, String itemName, int handCount, double unit,
                            double available, double required, int take, double actual,
                            double overpay, double pot, int affordable) {
    }

    private record PrepareResult(Prepared prepared, String error) {
        static PrepareResult ok(Prepared prepared) {
            return new PrepareResult(prepared, null);
        }

        static PrepareResult error(String message) {
            return new PrepareResult(null, message);
        }
    }

    private record PendingQuote(Prepared prepared, long at) {
    }

    public record BetOutcome(boolean ok, String error, String dayId, int guess, int tickets,
                             double paid, double overpay, double pot, String paidEncoded) {
        static BetOutcome error(String message) {
            return new BetOutcome(false, message, "", 0, 0, 0, 0, 0, "");
        }

        static BetOutcome ok(String dayId, int guess, int tickets, double paid,
                             double overpay, double pot, String paidEncoded) {
            return new BetOutcome(true, "", dayId, guess, tickets, paid, overpay, pot, paidEncoded);
        }
    }

    public record CollectOutcome(boolean ok, String error, List<ItemStack> items, int claims,
                                 boolean remaining) {
        static CollectOutcome error(String message) {
            return new CollectOutcome(false, message, List.of(), 0, false);
        }

        static CollectOutcome ok(List<ItemStack> items, int claims, boolean remaining) {
            return new CollectOutcome(true, "", List.copyOf(items), claims, remaining);
        }
    }
}
