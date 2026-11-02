package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全员同意制的单例入口（F-35~F-38）。
 *
 * <p>同一时间只允许一个进行中的投票；状态写
 * {@code config/samsara/season-advance.json}，重启后从该文件恢复。
 * 计票/过期/JSON 等纯逻辑在 {@link SeasonAdvanceVote}，本类只负责 Minecraft 侧接线。
 */
public final class SeasonAdvanceVoteManager {
    private static final long CHECK_INTERVAL_MS = 20_000L;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SeasonAdvanceVoteManager instance;

    private final Path file;
    private SeasonAdvanceVote active;
    private long lastCheckAt;

    private SeasonAdvanceVoteManager() {
        file = StoragePaths.configRoot().resolve("season-advance.json");
        active = SeasonAdvanceVote.load(file);
        if (active != null) {
            Samsara.LOGGER.info("[周目管理 samsara] 已恢复开新周目投票：{} 发起（{}）",
                    active.initiatorName, active.mode);
        } else if (java.nio.file.Files.exists(file)) {
            Samsara.LOGGER.warn("[周目管理 samsara] {} 无法恢复为进行中投票（可能损坏或已结束），保留原文件",
                    file);
        }
    }

    public static synchronized SeasonAdvanceVoteManager getInstance() {
        if (instance == null) {
            instance = new SeasonAdvanceVoteManager();
        }
        return instance;
    }

    /** 当前进行中的投票；没有时为 null。 */
    public synchronized SeasonAdvanceVote active() {
        return active;
    }

    // ---- 命令入口 ----

    /**
     * {@code /samsara season advance [same|new]}。
     * 只允许玩家发起；有管理员在线、在线 ≤1、已有 pending 时拒绝。
     */
    public synchronized String requestAdvance(MinecraftServer server, ServerPlayer player, String mode) {
        long now = System.currentTimeMillis();
        expireIfNeeded(server, now);
        if (player == null) {
            return "只有游戏内玩家可以发起开新周目投票；控制台/RCON 请直接使用 /samsara reset same|new。";
        }
        if (active != null) {
            return "已有进行中的开新周目投票，本次 advance 未覆盖。\n" + statusText(server);
        }
        String normalized = normalizeMode(mode);
        if (normalized == null) {
            return "未知模式：" + mode + "。用法：/samsara season advance [same|new]";
        }
        Map<UUID, String> online = onlinePlayers(server);
        boolean adminOnline = adminOnline(server);
        SeasonAdvanceVote.StartCheck check = SeasonAdvanceVote.checkStart(
                true, adminOnline, online.size(), false);
        if (check == SeasonAdvanceVote.StartCheck.ADMIN_ONLINE) {
            return "当前有管理员在线，请管理员直接执行 /samsara reset "
                    + (SeasonAdvanceVote.MODE_NEW.equals(normalized) ? "new" : "same")
                    + "（管理员可直接开新周目，无需投票）。";
        }
        if (check == SeasonAdvanceVote.StartCheck.NOT_ENOUGH_PLAYERS) {
            return "当前在线玩家只有 " + online.size() + " 人；至少 2 人在线才能发起（避免单人自开）。";
        }
        if (check != SeasonAdvanceVote.StartCheck.OK) {
            return "当前不能发起开新周目投票。";
        }

        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(server, null);
        StackManifest manifest = stack.manifest(server);
        StackLayer top = manifest.top();
        String playerLayerId = StackPaths.layerIdOf(player.level().dimension());
        StackLayer playerLayer = playerLayerId == null ? null : manifest.byId(playerLayerId);

        String layerId;
        Long seed = null;
        String levelType = "normal";
        boolean archiveData = false;
        if (SeasonAdvanceVote.MODE_NEW.equals(normalized)) {
            if (top == null || !top.id.equals(playerLayerId)) {
                return "新世界会生成新的栈顶；请先 /samsara world enter world 再发起。";
            }
            layerId = top.id;
        } else {
            if (playerLayer == null) {
                return "无法确定你当前所在的栈层，请稍后再试。";
            }
            layerId = playerLayer.id;
        }

        SeasonAdvanceVote vote = SeasonAdvanceVote.create(normalized, player.getUUID(),
                player.getScoreboardName(), online, layerId, seed, levelType, archiveData, now);
        if (!vote.save(file)) {
            return "投票状态写入失败（config/samsara/season-advance.json），请检查磁盘权限后重试。";
        }
        active = vote;
        broadcast(server, "🗳 " + vote.initiatorName + " 发起了开新周目投票（"
                + modeLabel(vote.mode) + "）· 输入 /samsara season agree 同意 · "
                + "48 小时内未全员同意将自动过期");
        return "已发起开新周目投票。\n" + statusText(server);
    }

    /** {@code /samsara season agree}：同意当前投票；全部同意后执行 reset。 */
    public synchronized String agree(MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return "只有游戏内玩家可以同意开新周目投票。";
        }
        long now = System.currentTimeMillis();
        if (expireIfNeeded(server, now)) {
            return "投票已过期，没有进行中的投票。";
        }
        if (active == null) {
            return noVoteText();
        }
        SeasonAdvanceVote.AgreeResult result = active.agree(player.getUUID(),
                player.getScoreboardName(), now);
        if (result == SeasonAdvanceVote.AgreeResult.EXPIRED) {
            expireIfNeeded(server, now);
            return "投票已过期，没有进行中的投票。";
        }
        if (result == SeasonAdvanceVote.AgreeResult.NOT_PENDING) {
            return "当前投票已结束，没有进行中的投票。";
        }

        Map<UUID, String> online = onlinePlayers(server);
        if (result != SeasonAdvanceVote.AgreeResult.ALREADY_VOTED) {
            saveActive();
            int required = active.missingNames(online).size() + active.votes.size();
            broadcast(server, "🗳 " + player.getScoreboardName() + " 已同意开新周目（"
                    + active.votes.size() + "/" + required + "）");
        }
        if (active.allAgreed(online)) {
            return execute(server, active);
        }
        saveActive();
        List<String> missing = active.missingNames(online);
        return "已记录你的同意。\n· 已同意 " + active.votes.size() + " 人\n"
                + "· 未同意：" + joinNames(missing)
                + "\n· 使用 /samsara season vote-status 查看完整状态";
    }

    /** {@code /samsara season cancel}：发起者撤回，或管理员取消进行中的投票。 */
    public synchronized String cancel(MinecraftServer server, ServerPlayer player) {
        long now = System.currentTimeMillis();
        if (expireIfNeeded(server, now)) {
            return "投票已过期，没有进行中的投票。";
        }
        if (active == null) {
            return noVoteText();
        }
        boolean admin = player == null
                || player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
        boolean initiator = player != null && active.isInitiator(player.getUUID());
        if (!SeasonAdvanceVote.canCancel(admin, initiator)) {
            return "只有本次投票的发起者或管理员可以取消/撤回投票。";
        }
        String actor = player == null ? "控制台" : player.getScoreboardName();
        String initiatorName = active.initiatorName;
        String mode = modeLabel(active.mode);
        active = null;
        SeasonAdvanceVote.deleteFile(file);
        String verb = initiator ? "撤回" : "取消";
        broadcast(server, "🗳 " + (admin && !initiator ? "管理员 " : "") + actor
                + " 已" + verb + "开新周目投票（发起者 " + initiatorName + " · " + mode + "）");
        return "已" + verb + "开新周目投票（发起者 " + initiatorName + " · " + mode + "）。";
    }

    /** {@code /samsara season vote-status}：发起者、模式、名单、剩余时间。 */
    public synchronized String statusText(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (expireIfNeeded(server, now)) {
            return noVoteText();
        }
        if (active == null) {
            return noVoteText();
        }
        Map<UUID, String> online = onlinePlayers(server);
        List<String> agreed = active.agreedNames();
        List<String> missing = active.missingNames(online);
        StringBuilder sb = new StringBuilder("开新周目投票");
        sb.append("\n· 发起者：").append(active.initiatorName)
                .append("（").append(active.initiatorUuid).append("）");
        sb.append("\n· 模式：").append(modeLabel(active.mode));
        if (SeasonAdvanceVote.MODE_SAME.equals(active.mode)) {
            sb.append(" · 目标栈层 ").append(active.layerId);
        } else {
            sb.append(" · 种子 ").append(active.seed == null ? "随机" : active.seed)
                    .append(" · level-type ").append(active.levelType)
                    .append(" · archiveData ").append(active.archiveData);
        }
        sb.append("\n· 状态：").append(active.status);
        sb.append("\n· 发起：").append(fmt(active.createdAt))
                .append(" · 截止：").append(fmt(active.expiresAt))
                .append(" · 剩余：").append(DurationUtil.formatMs(active.remainingMs(now)));
        sb.append("\n· 已同意（").append(agreed.size()).append("）：").append(joinNames(agreed));
        sb.append("\n· 未同意（").append(missing.size()).append("）：").append(joinNames(missing));
        if (active.executionError != null && !active.executionError.isBlank()) {
            sb.append("\n· 上次执行失败：").append(active.executionError)
                    .append("（投票已保留，可再次 /samsara season agree 重试）");
        }
        sb.append("\n· 使用 /samsara season agree 同意；48 小时内未全员同意将自动过期。");
        return sb.toString();
    }

    // ---- 事件钩子 ----

    /** 每 20 秒检查一次过期，并把期间新加入的在线玩家并入 eligible。 */
    public synchronized void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckAt = now;
        if (expireIfNeeded(server, now) || active == null) {
            return;
        }
        List<String> added = active.addEligible(onlinePlayers(server));
        if (!added.isEmpty()) {
            saveActive();
            broadcast(server, "🗳 新玩家 " + joinNames(added)
                    + " 加入开新周目投票，需要 /samsara season agree 同意");
        }
    }

    /** 玩家加入时立刻并入 eligible；离线玩家已投的票不删除。 */
    public synchronized void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (expireIfNeeded(server, now) || active == null) {
            return;
        }
        Map<UUID, String> one = new LinkedHashMap<>();
        one.put(player.getUUID(), player.getScoreboardName());
        List<String> added = active.addEligible(one);
        if (!added.isEmpty()) {
            saveActive();
            broadcast(server, "🗳 新玩家 " + joinNames(added)
                    + " 加入开新周目投票，需要 /samsara season agree 同意");
        }
    }

    // ---- 执行 ----

    private String execute(MinecraftServer server, SeasonAdvanceVote vote) {
        long now = System.currentTimeMillis();
        Map<UUID, String> online = onlinePlayers(server);
        if (!vote.canExecute(online, now)) {
            return "投票尚未满足执行条件。\n" + statusText(server);
        }
        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(server, null);
        StackManifest manifest = stack.manifest(server);
        StackLayer top = manifest.top();
        StackLayer target = SeasonAdvanceVote.MODE_SAME.equals(vote.mode)
                ? manifest.byId(vote.layerId) : top;
        boolean onTop = top != null && target != null && target.top;
        boolean pendingReset = SeasonManager.getInstance().hasPendingReset();
        boolean pendingNew = StackBootstrap.hasPendingNew(server);
        boolean rewindRunning = RewindEngine.getInstance().running();

        String problem = vote.validateExecution(target != null, onTop,
                pendingReset, pendingNew, rewindRunning, now);
        if (problem != null) {
            return executionFailed(server, vote, problem);
        }

        String result;
        if (SeasonAdvanceVote.MODE_SAME.equals(vote.mode)) {
            result = SeasonManager.getInstance().resetSame(server, target);
            if (!result.startsWith("已开始同世界重置")
                    && !result.startsWith("没有需要倒流的区块")) {
                return executionFailed(server, vote, result);
            }
        } else {
            result = SeasonManager.getInstance().resetNew(server, vote.seed,
                    vote.levelType, vote.archiveData);
            if (!result.startsWith("已安排新世界入栈")) {
                return executionFailed(server, vote, result);
            }
        }

        vote.markPassed();
        active = null;
        SeasonAdvanceVote.deleteFile(file);
        broadcast(server, "✅ 全员同意，开新周目已执行（" + modeLabel(vote.mode) + "）\n" + result);
        return "✅ 全员同意，开新周目已执行（" + modeLabel(vote.mode) + "）\n" + result;
    }

    private String executionFailed(MinecraftServer server, SeasonAdvanceVote vote, String reason) {
        vote.markExecutionError(reason);
        saveActive();
        broadcast(server, "⚠ 开新周目投票已达成，但执行失败：" + reason
                + "\n· 投票已保留；修复条件后可再次 /samsara season agree 重试");
        return "⚠ 执行失败：" + reason
                + "\n· 投票已保留（状态文件未删除），修复条件后可再次 /samsara season agree 重试。";
    }

    // ---- 内部 ----

    private boolean expireIfNeeded(MinecraftServer server, long now) {
        if (active == null || !active.expired(now)) {
            return false;
        }
        String initiator = active.initiatorName;
        active = null;
        SeasonAdvanceVote.deleteFile(file);
        broadcast(server, "⌛ 开新周目投票已过期（48 小时内未全员同意；发起者 "
                + initiator + "）");
        return true;
    }

    private void saveActive() {
        if (active != null && !active.save(file)) {
            Samsara.LOGGER.warn("[周目管理 samsara] 开新周目投票状态写入失败：{}", file);
        }
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SeasonAdvanceVote.MODE_SAME;
        }
        String value = mode.trim().toLowerCase();
        return switch (value) {
            case SeasonAdvanceVote.MODE_SAME, SeasonAdvanceVote.MODE_NEW -> value;
            default -> null;
        };
    }

    private static Map<UUID, String> onlinePlayers(MinecraftServer server) {
        Map<UUID, String> online = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.put(player.getUUID(), player.getScoreboardName());
        }
        return online;
    }

    private static boolean adminOnline(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
                return true;
            }
        }
        return false;
    }

    private static String noVoteText() {
        return "没有进行中的开新周目投票。\n"
                + "· 无管理员且在线 >1 人时，可用 /samsara season advance [same|new] 发起。";
    }

    private static String modeLabel(String mode) {
        return SeasonAdvanceVote.MODE_NEW.equals(mode) ? "new（新世界入栈）" : "same（同世界重置）";
    }

    private static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "无";
        }
        List<String> cleaned = new ArrayList<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                cleaned.add(name);
            }
        }
        return cleaned.isEmpty() ? "无" : String.join("、", cleaned);
    }

    private static String fmt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("[周目管理] " + message), false);
    }
}
