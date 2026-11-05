package com.shenqianye.samsara.announce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一条 Web 可编辑公告。
 *
 * <p>文本支持 {@code &} 传统颜色/格式码、{@code &#RRGGBB}、换行与变量
 * {@code {player}}、{@code {world}}、{@code {online}} 等。公告本身只是数据，
 * 由 {@link AnnouncementService} 负责校验、调度与推送。
 */
public final class Announcement {
    public String id = "";
    public String name = "";
    public boolean enabled = true;

    /** manual / interval / schedule / join */
    public String trigger = "manual";
    /** interval 触发间隔（秒），最小 10。 */
    public int intervalSeconds = 300;
    /** schedule 触发时间：yyyy-MM-dd HH:mm（服务器本地时区）。 */
    public String startAt = "";

    /** all / permission / world / dimension / players */
    public String target = "all";
    public String targetValue = "";

    public boolean chat = true;
    public String chatText = "&e[公告] &f{player}，欢迎来到服务器！";
    public boolean actionbar = false;
    public String actionbarText = "";
    public boolean title = false;
    public String titleText = "";
    public String subtitleText = "";
    public boolean bossbar = false;
    public String bossbarText = "";
    public String bossbarColor = "BLUE";
    public String bossbarOverlay = "PROGRESS";
    public int durationSeconds = 8;
    public int fadeInTicks = 10;
    public int stayTicks = 70;
    public int fadeOutTicks = 20;

    public boolean sound = false;
    public String soundId = "minecraft:ui.toast.in";
    public double soundVolume = 1.0;
    public double soundPitch = 1.0;

    /** none / run / suggest / open_url / copy */
    public String clickAction = "none";
    public String clickValue = "";
    public String hoverText = "";
    /** 点击 run 事件要求玩家具备的权限路径；留空时要求 OP。 */
    public String clickPermission = "";

    public int priority = 0;

    public Announcement normalize() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        id = id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        trigger = lower(trigger, "manual");
        target = lower(target, "all");
        bossbarColor = upper(bossbarColor, "BLUE");
        bossbarOverlay = upper(bossbarOverlay, "PROGRESS");
        clickAction = lower(clickAction, "none");
        chatText = text(chatText);
        actionbarText = text(actionbarText);
        titleText = text(titleText);
        subtitleText = text(subtitleText);
        bossbarText = text(bossbarText);
        startAt = startAt == null ? "" : startAt.trim();
        targetValue = targetValue == null ? "" : targetValue.trim();
        soundId = soundId == null || soundId.isBlank() ? "minecraft:ui.toast.in" : soundId.trim();
        clickValue = clickValue == null ? "" : clickValue.trim();
        hoverText = text(hoverText);
        clickPermission = clickPermission == null ? "" : clickPermission.trim();
        intervalSeconds = Math.max(0, intervalSeconds);
        durationSeconds = Math.max(1, Math.min(3600, durationSeconds));
        fadeInTicks = Math.max(0, Math.min(200, fadeInTicks));
        stayTicks = Math.max(1, Math.min(1200, stayTicks));
        fadeOutTicks = Math.max(0, Math.min(200, fadeOutTicks));
        soundVolume = Math.max(0.0, Math.min(1.0, soundVolume));
        soundPitch = Math.max(0.5, Math.min(2.0, soundPitch));
        priority = Math.max(-100, Math.min(100, priority));
        return this;
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (id == null || id.isBlank()) {
            errors.add("id 不能为空");
        }
        if (!List.of("manual", "interval", "schedule", "join").contains(trigger)) {
            errors.add("trigger 必须是 manual/interval/schedule/join");
        }
        if ("interval".equals(trigger) && intervalSeconds < 10) {
            errors.add("interval 触发间隔至少 10 秒");
        }
        if ("schedule".equals(trigger)) {
            if (startAt == null || startAt.isBlank()) {
                errors.add("schedule 必须填写 startAt");
            } else {
                try {
                    java.time.LocalDateTime.parse(startAt.replace(' ', 'T'));
                } catch (RuntimeException e) {
                    errors.add("startAt 格式应为 yyyy-MM-dd HH:mm");
                }
            }
        }
        if (!List.of("all", "permission", "world", "dimension", "players").contains(target)) {
            errors.add("target 必须是 all/permission/world/dimension/players");
        }
        if (!"all".equals(target) && (targetValue == null || targetValue.isBlank())) {
            errors.add("targetValue 不能为空");
        }
        if (!List.of("none", "run", "suggest", "open_url", "copy").contains(clickAction)) {
            errors.add("clickAction 必须是 none/run/suggest/open_url/copy");
        }
        if (!"none".equals(clickAction) && (clickValue == null || clickValue.isBlank())) {
            errors.add("clickValue 不能为空");
        }
        if (!List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE")
                .contains(bossbarColor)) {
            errors.add("bossbarColor 不合法");
        }
        if (!List.of("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20")
                .contains(bossbarOverlay)) {
            errors.add("bossbarOverlay 不合法");
        }
        if ((chat && chatText.isBlank()) && (actionbar && actionbarText.isBlank())
                && (title && titleText.isBlank() && subtitleText.isBlank())
                && (bossbar && bossbarText.isBlank()) && !sound) {
            errors.add("至少启用一种展示形式并填写内容");
        }
        return errors;
    }

    public Announcement copy() {
        Announcement copy = new Announcement();
        copy.id = id;
        copy.name = name;
        copy.enabled = enabled;
        copy.trigger = trigger;
        copy.intervalSeconds = intervalSeconds;
        copy.startAt = startAt;
        copy.target = target;
        copy.targetValue = targetValue;
        copy.chat = chat;
        copy.chatText = chatText;
        copy.actionbar = actionbar;
        copy.actionbarText = actionbarText;
        copy.title = title;
        copy.titleText = titleText;
        copy.subtitleText = subtitleText;
        copy.bossbar = bossbar;
        copy.bossbarText = bossbarText;
        copy.bossbarColor = bossbarColor;
        copy.bossbarOverlay = bossbarOverlay;
        copy.durationSeconds = durationSeconds;
        copy.fadeInTicks = fadeInTicks;
        copy.stayTicks = stayTicks;
        copy.fadeOutTicks = fadeOutTicks;
        copy.sound = sound;
        copy.soundId = soundId;
        copy.soundVolume = soundVolume;
        copy.soundPitch = soundPitch;
        copy.clickAction = clickAction;
        copy.clickValue = clickValue;
        copy.hoverText = hoverText;
        copy.clickPermission = clickPermission;
        copy.priority = priority;
        return copy;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value, String fallback) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? fallback : text;
    }

    private static String upper(String value, String fallback) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return text.isEmpty() ? fallback : text;
    }
}
