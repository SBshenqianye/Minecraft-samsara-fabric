package com.shenqianye.samsara.announce;

import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公告服务：Web 编辑、手动/定时/进服推送、聊天/ActionBar/Title/BossBar/声音。
 *
 * <p>公告数据写入 {@code config/samsara/announcements.json}；所有发送都只在服务器
 * 线程或 Web 请求线程调用，BossBar 到期由 {@link #tick(MinecraftServer)} 清理。
 */
public final class AnnouncementService {
    private static final AnnouncementService INSTANCE = new AnnouncementService();

    private final AnnouncementStore store = new AnnouncementStore();
    private volatile List<Announcement> cache;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<String, ActiveBar> bars = new ConcurrentHashMap<>();
    private final Set<String> firedSchedule = ConcurrentHashMap.newKeySet();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();

    private AnnouncementService() {
    }

    public static AnnouncementService getInstance() {
        return INSTANCE;
    }

    public AnnouncementStore store() {
        return store;
    }

    public synchronized List<Announcement> announcements() {
        List<Announcement> current = cache;
        if (current == null) {
            current = store.load();
            cache = current;
        }
        return current;
    }

    public Map<String, Object> model() {
        List<Announcement> announcements = announcements();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", store.file().toString());
        out.put("announcements", announcements);
        out.put("variables", List.of("player", "display", "world", "dimension",
                "online", "time", "date", "prefix"));
        out.put("triggers", List.of("manual", "interval", "schedule", "join"));
        out.put("targets", List.of("all", "permission", "world", "dimension", "players"));
        out.put("colors", List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"));
        out.put("overlays", List.of("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20"));
        out.put("clickActions", List.of("none", "run", "suggest", "open_url", "copy"));
        out.put("soundSuggestions", List.of("minecraft:ui.toast.in", "minecraft:entity.player.levelup",
                "minecraft:block.note_block.bell", "minecraft:entity.experience_orb.pickup"));
        out.put("history", List.copyOf(history));
        return out;
    }

    public synchronized Map<String, Object> save(List<Announcement> announcements) {
        List<String> errors = AnnouncementStore.validateAll(announcements);
        if (!errors.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "公告校验失败");
            out.put("errors", errors);
            return out;
        }
        for (Announcement announcement : announcements) {
            announcement.normalize();
        }
        try {
            store.save(announcements);
            cache = List.copyOf(announcements);
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
        lastSent.clear();
        firedSchedule.clear();
        Map<String, Object> out = model();
        out.put("message", "公告已保存（" + announcements.size() + " 条）");
        return out;
    }

    public Map<String, Object> preview(MinecraftServer server, Announcement announcement,
                                       String playerName) {
        if (announcement == null) {
            return error("缺少公告内容");
        }
        announcement.normalize();
        List<String> errors = announcement.validate();
        if (!errors.isEmpty()) {
            Map<String, Object> out = error("公告校验失败");
            out.put("errors", errors);
            return out;
        }
        ServerPlayer player = samplePlayer(server, playerName);
        return previewMap(announcement, player, server);
    }

    public Map<String, Object> send(MinecraftServer server, Announcement announcement,
                                    String playerName, boolean dryRun, boolean confirm,
                                    String actor) {
        if (server == null) {
            return error("服务器尚未启动");
        }
        if (announcement == null) {
            return error("缺少公告内容");
        }
        announcement.normalize();
        List<String> errors = announcement.validate();
        if (!errors.isEmpty()) {
            Map<String, Object> out = error("公告校验失败");
            out.put("errors", errors);
            return out;
        }
        List<ServerPlayer> recipients = resolveTargets(server, announcement);
        if (playerName != null && !playerName.isBlank()) {
            ServerPlayer one = playerByName(server, playerName);
            recipients = one == null ? List.of() : List.of(one);
        }
        if (recipients.isEmpty()) {
            return error("没有匹配的在线玩家");
        }
        ServerPlayer sample = recipients.get(0);
        Map<String, Object> preview = previewMap(announcement, sample, server);
        if (dryRun || !confirm) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dryRun", true);
            out.put("requiresConfirmation", true);
            out.put("recipients", recipients.size());
            out.put("recipientNames", recipients.stream().map(ServerPlayer::getScoreboardName).toList());
            out.put("preview", preview);
            out.put("message", "预览：" + recipients.size() + " 名玩家；确认后才会真正推送");
            return out;
        }
        for (ServerPlayer player : recipients) {
            deliver(server, announcement, player);
        }
        record(announcement, actor, recipients);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dryRun", false);
        out.put("recipients", recipients.size());
        out.put("recipientNames", recipients.stream().map(ServerPlayer::getScoreboardName).toList());
        out.put("message", "已推送「" + announcement.name + "」给 " + recipients.size() + " 名玩家");
        return out;
    }

    public Map<String, Object> stop(String id) {
        if (id == null || id.isBlank()) {
            bars.values().forEach(active -> active.bar().removeAllPlayers());
            bars.clear();
            return success("已停止全部 BossBar 公告");
        }
        ActiveBar active = bars.remove(id);
        if (active != null) {
            active.bar().removeAllPlayers();
        }
        return success("已停止公告 " + id);
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        bars.entrySet().removeIf(entry -> {
            if (entry.getValue().endMs() <= now) {
                entry.getValue().bar().removeAllPlayers();
                return true;
            }
            return false;
        });
        List<Announcement> announcements = announcements();
        for (Announcement announcement : announcements) {
            if (!announcement.enabled) {
                continue;
            }
            if ("interval".equals(announcement.trigger)) {
                int interval = Math.max(10, announcement.intervalSeconds);
                Long last = lastSent.get(announcement.id);
                if (last == null) {
                    lastSent.put(announcement.id, now);
                } else if (now - last >= interval * 1000L) {
                    lastSent.put(announcement.id, now);
                    sendScheduled(server, announcement);
                }
            } else if ("schedule".equals(announcement.trigger)) {
                long start = scheduleMillis(announcement.startAt);
                if (start > 0 && now >= start && !firedSchedule.contains(announcement.id)
                        && sendScheduled(server, announcement)) {
                    firedSchedule.add(announcement.id);
                }
            }
        }
    }

    public void onJoin(ServerPlayer player) {
        if (player == null || player.level().getServer() == null) {
            return;
        }
        for (Announcement announcement : announcements()) {
            if (announcement.enabled && "join".equals(announcement.trigger)
                    && matchesTarget(player, announcement)) {
                deliver(player.level().getServer(), announcement, player);
            }
        }
    }

    private boolean sendScheduled(MinecraftServer server, Announcement announcement) {
        List<ServerPlayer> recipients = resolveTargets(server, announcement);
        for (ServerPlayer player : recipients) {
            deliver(server, announcement, player);
        }
        if (!recipients.isEmpty()) {
            record(announcement, "scheduler", recipients);
        }
        return !recipients.isEmpty();
    }

    private void deliver(MinecraftServer server, Announcement a, ServerPlayer player) {
        if (a.chat && !a.chatText.isBlank()) {
            for (Component line : AnnouncementRenderer.chatLines(a, player, server)) {
                player.sendSystemMessage(line, false);
            }
        }
        if (a.actionbar && !a.actionbarText.isBlank()) {
            player.connection.send(new ClientboundSetActionBarTextPacket(
                    AnnouncementRenderer.component(a.actionbarText, a, player, server)));
        }
        if (a.title && (!a.titleText.isBlank() || !a.subtitleText.isBlank())) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(
                    a.fadeInTicks, a.stayTicks, a.fadeOutTicks));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    AnnouncementRenderer.component(a.subtitleText, a, player, server)));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    AnnouncementRenderer.component(a.titleText, a, player, server)));
        }
        if (a.bossbar && !a.bossbarText.isBlank()) {
            showBossBar(a, player, server);
        }
        if (a.sound) {
            playSound(a, player);
        }
    }

    private void showBossBar(Announcement a, ServerPlayer player, MinecraftServer server) {
        ActiveBar existing = bars.remove(a.id);
        if (existing != null) {
            existing.bar().removeAllPlayers();
        }
        try {
            ServerBossEvent bar = new ServerBossEvent(java.util.UUID.randomUUID(),
                    AnnouncementRenderer.component(a.bossbarText, a, player, server),
                    BossEvent.BossBarColor.valueOf(a.bossbarColor),
                    BossEvent.BossBarOverlay.valueOf(a.bossbarOverlay));
            bar.setProgress(1.0f);
            bar.setVisible(true);
            bar.addPlayer(player);
            bars.put(a.id, new ActiveBar(bar, System.currentTimeMillis() + a.durationSeconds * 1000L));
        } catch (RuntimeException e) {
            // 非法颜色/overlay 已在保存时拒绝；这里只兜底，不影响聊天公告。
        }
    }

    private void playSound(Announcement a, ServerPlayer player) {
        try {
            Identifier id = Identifier.parse(a.soundId);
            Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.get(id).orElse(null);
            if (holder == null) {
                return;
            }
            player.connection.send(new ClientboundSoundPacket(holder, SoundSource.UI,
                    player.getX(), player.getY(), player.getZ(),
                    (float) a.soundVolume, (float) a.soundPitch,
                    player.getRandom().nextLong()));
        } catch (RuntimeException ignored) {
            // 声音不存在或格式错误：静默跳过。
        }
    }

    private Map<String, Object> previewMap(Announcement a, ServerPlayer player,
                                           MinecraftServer server) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("player", player == null ? "（无在线玩家）" : player.getScoreboardName());
        List<String> chat = new ArrayList<>();
        for (String line : a.chatText.split("\\n", -1)) {
            chat.add(AnnouncementRenderer.preview(line, a, player, server));
        }
        out.put("chat", chat);
        out.put("actionbar", AnnouncementRenderer.preview(a.actionbarText, a, player, server));
        out.put("title", AnnouncementRenderer.preview(a.titleText, a, player, server));
        out.put("subtitle", AnnouncementRenderer.preview(a.subtitleText, a, player, server));
        out.put("bossbar", AnnouncementRenderer.preview(a.bossbarText, a, player, server));
        out.put("sound", a.sound ? a.soundId : "");
        out.put("channels", channels(a));
        return out;
    }

    private List<String> channels(Announcement a) {
        List<String> out = new ArrayList<>();
        if (a.chat) out.add("chat");
        if (a.actionbar) out.add("actionbar");
        if (a.title) out.add("title");
        if (a.bossbar) out.add("bossbar");
        if (a.sound) out.add("sound");
        return out;
    }

    private List<ServerPlayer> resolveTargets(MinecraftServer server, Announcement a) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (matchesTarget(player, a)) {
                out.add(player);
            }
        }
        return out;
    }

    private boolean matchesTarget(ServerPlayer player, Announcement a) {
        return switch (a.target) {
            case "all" -> true;
            case "players" -> {
                String name = player.getScoreboardName().toLowerCase(Locale.ROOT);
                boolean matched = false;
                for (String item : a.targetValue.split(",")) {
                    if (name.equals(item.trim().toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
                yield matched;
            }
            case "permission" -> PermissionChecker.check(player.createCommandSourceStack(), a.targetValue);
            case "world", "dimension" -> {
                String dimension = player.level().dimension().identifier().toString();
                String value = a.targetValue.trim();
                yield dimension.equals(value) || dimension.endsWith(":" + value)
                        || dimension.substring(dimension.indexOf(':') + 1).equals(value);
            }
            default -> true;
        };
    }

    private ServerPlayer samplePlayer(MinecraftServer server, String name) {
        if (server == null) {
            return null;
        }
        if (name != null && !name.isBlank()) {
            ServerPlayer one = playerByName(server, name);
            if (one != null) {
                return one;
            }
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    private ServerPlayer playerByName(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getScoreboardName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private long scheduleMillis(String startAt) {
        try {
            return LocalDateTime.parse(startAt.trim().replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private synchronized void record(Announcement a, String actor, List<ServerPlayer> recipients) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", System.currentTimeMillis());
        row.put("id", a.id);
        row.put("name", a.name);
        row.put("actor", actor == null || actor.isBlank() ? "web" : actor);
        row.put("recipients", recipients.size());
        row.put("channels", channels(a));
        history.addFirst(row);
        while (history.size() > 50) {
            history.removeLast();
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message == null ? "" : message);
        return out;
    }

    private static Map<String, Object> success(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", message == null ? "" : message);
        return out;
    }

    private record ActiveBar(ServerBossEvent bar, long endMs) {
    }
}
