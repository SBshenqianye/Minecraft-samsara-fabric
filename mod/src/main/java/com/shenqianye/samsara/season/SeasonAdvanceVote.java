package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全员同意制投票的纯状态与纯逻辑（不依赖 Minecraft API）。
 *
 * <p>状态文件：{@code config/samsara/season-advance.json}。同一时间只允许一个
 * {@code pending} 投票；由 {@link SeasonAdvanceVoteManager} 负责单例、命令与 reset 执行。
 *
 * <p>本类只处理计票、名单合并、过期判定、执行前校验和 JSON 往返，方便单测。
 */
public final class SeasonAdvanceVote {
    public static final int FORMAT = 1;
    /** 48 小时。 */
    public static final long TTL_MS = 48L * 60L * 60L * 1000L;

    public static final String MODE_SAME = "same";
    public static final String MODE_NEW = "new";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PASSED = "passed";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_FAILED = "failed";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int format = FORMAT;
    public String id = "";
    /** same | new */
    public String mode = MODE_SAME;
    public String initiatorUuid = "";
    public String initiatorName = "";
    public long createdAt;
    public long expiresAt;
    /** pending | passed | expired | failed */
    public String status = STATUS_PENDING;
    /** UUID → 玩家名。发起时的在线玩家 + 投票期间新加入的玩家。 */
    public Map<String, String> eligible = new LinkedHashMap<>();
    /** UUID → 同意记录（name/at）。 */
    public Map<String, Vote> votes = new LinkedHashMap<>();
    /** same：发起时所在栈层 id；new：发起时的栈顶 id。 */
    public String layerId = "";
    /** new 模式参数；same 为 null。 */
    public Long seed;
    public String levelType = "normal";
    public boolean archiveData;
    /** 执行失败原因；保留投票时写入。 */
    public String executionError;

    /** 单条同意记录。 */
    public static final class Vote {
        public String name = "";
        public long at;

        public Vote() {
        }

        public Vote(String name, long at) {
            this.name = name;
            this.at = at;
        }
    }

    public enum StartCheck {
        OK,
        NOT_PLAYER,
        ADMIN_ONLINE,
        NOT_ENOUGH_PLAYERS,
        ALREADY_PENDING
    }

    public enum AgreeResult {
        VOTED,
        ALREADY_VOTED,
        ADDED_AND_VOTED,
        NOT_PENDING,
        EXPIRED
    }

    private SeasonAdvanceVote() {
    }

    /**
     * 发起前的纯规则检查。管理员在线优先于人数检查；已有进行中投票时不覆盖。
     */
    public static StartCheck checkStart(boolean fromPlayer, boolean adminOnline,
                                        int onlineCount, boolean hasPending) {
        if (!fromPlayer) {
            return StartCheck.NOT_PLAYER;
        }
        if (hasPending) {
            return StartCheck.ALREADY_PENDING;
        }
        if (adminOnline) {
            return StartCheck.ADMIN_ONLINE;
        }
        if (onlineCount <= 1) {
            return StartCheck.NOT_ENOUGH_PLAYERS;
        }
        return StartCheck.OK;
    }

    /**
     * 创建投票：当前在线玩家全部进入 eligible，发起者自动投同意票。
     *
     * @param seed new 模式种子；null = 随机
     */
    public static SeasonAdvanceVote create(String mode, UUID initiatorUuid, String initiatorName,
                                           Map<UUID, String> online, String layerId,
                                           Long seed, String levelType, boolean archiveData,
                                           long now) {
        SeasonAdvanceVote vote = new SeasonAdvanceVote();
        vote.id = UUID.randomUUID().toString();
        vote.mode = MODE_NEW.equals(mode) ? MODE_NEW : MODE_SAME;
        vote.initiatorUuid = initiatorUuid == null ? "" : initiatorUuid.toString();
        vote.initiatorName = initiatorName == null ? "" : initiatorName;
        vote.createdAt = now;
        vote.expiresAt = now + TTL_MS;
        vote.status = STATUS_PENDING;
        if (online != null) {
            for (Map.Entry<UUID, String> entry : online.entrySet()) {
                if (entry.getKey() != null) {
                    vote.eligible.put(entry.getKey().toString(),
                            entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
        if (initiatorUuid != null) {
            vote.eligible.putIfAbsent(initiatorUuid.toString(), vote.initiatorName);
            vote.votes.put(initiatorUuid.toString(), new Vote(vote.initiatorName, now));
        }
        vote.layerId = layerId == null ? "" : layerId;
        vote.seed = MODE_NEW.equals(vote.mode) ? seed : null;
        vote.levelType = levelType == null || levelType.isBlank() ? "normal" : levelType;
        vote.archiveData = archiveData;
        return vote;
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean expired(long now) {
        return createdAt > 0 && now - createdAt >= TTL_MS;
    }

    public long remainingMs(long now) {
        long remaining = createdAt + TTL_MS - now;
        return Math.max(0L, remaining);
    }

    /**
     * 把当前在线玩家并入 eligible。返回本次新增的玩家名，调用方据此广播一次。
     */
    public List<String> addEligible(Map<UUID, String> online) {
        List<String> added = new ArrayList<>();
        if (online == null) {
            return added;
        }
        for (Map.Entry<UUID, String> entry : online.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String uuid = entry.getKey().toString();
            String name = entry.getValue() == null ? "" : entry.getValue();
            if (!eligible.containsKey(uuid)) {
                eligible.put(uuid, name);
                added.add(name);
            } else if (!name.isBlank() && !name.equals(eligible.get(uuid))) {
                eligible.put(uuid, name);
            }
        }
        return added;
    }

    /** 同意；重复同意幂等。非 eligible 的当前在线玩家会先加入 eligible 再计票。 */
    public AgreeResult agree(UUID playerUuid, String playerName, long now) {
        if (!isPending()) {
            return AgreeResult.NOT_PENDING;
        }
        if (expired(now)) {
            return AgreeResult.EXPIRED;
        }
        if (playerUuid == null) {
            return AgreeResult.NOT_PENDING;
        }
        String uuid = playerUuid.toString();
        String name = playerName == null ? "" : playerName;
        boolean added = !eligible.containsKey(uuid);
        if (added) {
            eligible.put(uuid, name);
        } else if (!name.isBlank()) {
            eligible.put(uuid, name);
        }
        if (votes.containsKey(uuid)) {
            return AgreeResult.ALREADY_VOTED;
        }
        votes.put(uuid, new Vote(name, now));
        return added ? AgreeResult.ADDED_AND_VOTED : AgreeResult.VOTED;
    }

    /** 未同意的玩家名：eligible ∪ 当前在线，按 eligible/在线顺序。 */
    public List<String> missingNames(Map<UUID, String> online) {
        List<String> missing = new ArrayList<>();
        Map<String, String> required = new LinkedHashMap<>(eligible);
        if (online != null) {
            for (Map.Entry<UUID, String> entry : online.entrySet()) {
                if (entry.getKey() != null) {
                    required.putIfAbsent(entry.getKey().toString(),
                            entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!votes.containsKey(entry.getKey())) {
                missing.add(entry.getValue());
            }
        }
        return missing;
    }

    public List<String> agreedNames() {
        List<String> names = new ArrayList<>();
        for (Vote vote : votes.values()) {
            names.add(vote.name == null || vote.name.isBlank() ? "未知玩家" : vote.name);
        }
        return names;
    }

    /** eligible ∪ 当前在线全部已同意。 */
    public boolean allAgreed(Map<UUID, String> online) {
        return missingNames(online).isEmpty();
    }

    /** 满足全部条件后才允许真正执行 reset。 */
    public boolean canExecute(Map<UUID, String> online, long now) {
        return isPending() && !expired(now) && allAgreed(online);
    }

    /**
     * 执行前校验。返回 null = 可以执行；否则为失败原因（保留投票并写入 executionError）。
     */
    public String validateExecution(boolean layerExists, boolean onTop,
                                    boolean pendingReset, boolean pendingNew,
                                    boolean rewindRunning, long now) {
        if (!isPending()) {
            return "投票状态不是 pending（" + status + "）";
        }
        if (expired(now)) {
            return "投票已过期";
        }
        if (rewindRunning) {
            return "已有进行中的时间倒流";
        }
        if (pendingReset) {
            return "已有待完成的同世界重置";
        }
        if (pendingNew) {
            return "已有待执行的新世界生成";
        }
        if (MODE_SAME.equals(mode)) {
            if (!layerExists) {
                return "目标栈层 " + layerId + " 已不在世界栈中";
            }
        } else if (MODE_NEW.equals(mode)) {
            if (!onTop) {
                return "只有栈顶可以生成新世界";
            }
        } else {
            return "未知的开新周目模式：" + mode;
        }
        return null;
    }

    /**
     * 记录执行失败原因，但保持 pending，便于条件恢复后再次 agree 重试；
     * 状态文件也仍可被重启恢复。
     */
    public void markExecutionError(String error) {
        executionError = error;
    }

    public void markPassed() {
        status = STATUS_PASSED;
        executionError = null;
    }

    // ---- JSON 持久化（原子写，损坏回退） ----

    public String toJson() {
        return GSON.toJson(this);
    }

    public static SeasonAdvanceVote fromJson(String json) {
        try {
            SeasonAdvanceVote vote = GSON.fromJson(json, SeasonAdvanceVote.class);
            if (vote == null) {
                return null;
            }
            vote.normalize();
            return vote;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取进行中的投票。文件不存在 / 损坏 / 已结束 → null；<b>不删除原文件</b>。
     */
    public static SeasonAdvanceVote load(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            SeasonAdvanceVote vote = fromJson(json);
            if (vote == null || !vote.isPending() || vote.id.isBlank() || vote.createdAt <= 0) {
                return null;
            }
            return vote;
        } catch (Exception e) {
            return null;
        }
    }

    /** 原子写：先写 {@code .tmp} 再 move；失败返回 false。 */
    public boolean save(Path file) {
        if (file == null) {
            return false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Windows 上目标已存在时 ATOMIC_MOVE 可能失败，退化为普通替换。
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 清除状态文件（成功执行 / 过期）。 */
    public static void deleteFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private void normalize() {
        if (format <= 0) {
            format = FORMAT;
        }
        if (mode == null || (!MODE_SAME.equals(mode) && !MODE_NEW.equals(mode))) {
            mode = MODE_SAME;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_PENDING;
        }
        if (id == null) {
            id = "";
        }
        if (initiatorUuid == null) {
            initiatorUuid = "";
        }
        if (initiatorName == null) {
            initiatorName = "";
        }
        if (eligible == null) {
            eligible = new LinkedHashMap<>();
        }
        for (Map.Entry<String, String> entry : eligible.entrySet()) {
            if (entry.getValue() == null) {
                entry.setValue("");
            }
        }
        if (votes == null) {
            votes = new LinkedHashMap<>();
        }
        votes.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (Vote vote : votes.values()) {
            if (vote.name == null) {
                vote.name = "";
            }
        }
        if (layerId == null) {
            layerId = "";
        }
        if (levelType == null || levelType.isBlank()) {
            levelType = "normal";
        }
        // 48 小时一律从 createdAt 计算，避免手工改 expiresAt 绕过。
        if (createdAt > 0) {
            expiresAt = createdAt + TTL_MS;
        }
        if (MODE_SAME.equals(mode)) {
            seed = null;
        }
    }
}
