package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.rewind.DiffViewService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一回看镜头：同时驱动玩家轨迹回看（{@link ViewManager}）与方块 Diff 回看
 * （{@link DiffViewService}）。
 *
 * <p>默认两者同时开始，可用 {@code --track=off} 或 {@code --diff=off} 单独关闭。
 * 启动顺序固定为 Diff 先、轨迹后：轨迹会话会捕获“已进入 Diff 旁观”的快照；
 * 停止顺序固定为轨迹先、Diff 后，最终恢复进入镜头前的原始状态。
 *
 * <p>原有 {@code track view} / {@code diff view} 命令保持兼容，不经过本服务。
 */
public final class CameraService {
    private static final CameraService INSTANCE = new CameraService();

    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    private CameraService() {
    }

    public static CameraService getInstance() {
        return INSTANCE;
    }

    private static final class Session {
        UUID viewer;
        boolean track;
        boolean diff;
        long startedAtMs;
    }

    public record StartRequest(List<String> targets, boolean all, String start,
                               String trackOptions, boolean track, boolean diff,
                               double speed, int diffRadius, String diffDimension,
                               String diffTo) {
    }

    public synchronized String start(MinecraftServer server, ServerPlayer viewer,
                                     StartRequest request, boolean allowOthers) {
        if (viewer == null) {
            return "镜头回看需要以玩家身份执行";
        }
        if (sessions.containsKey(viewer.getUUID())) {
            return "你已有进行中的镜头回看，请先 /samsara camera stop";
        }
        if (!request.track() && !request.diff()) {
            return "track 与 diff 至少保留一个；默认两者同时播放";
        }
        List<String> notes = new ArrayList<>();
        boolean diffStarted = false;
        if (request.diff()) {
            String diff = DiffViewService.getInstance().startGlobal(server, viewer,
                    request.diffDimension(), request.start(), request.diffTo(), request.speed(),
                    request.diffRadius());
            diffStarted = diff.startsWith("开始");
            if (diffStarted) {
                notes.add("Diff：" + diff);
            } else {
                notes.add("Diff 未开始：" + diff);
            }
        }
        boolean trackStarted = false;
        if (request.track()) {
            String track = ViewManager.getInstance().start(server, viewer, request.targets(),
                    request.all(), request.start(), request.trackOptions(), allowOthers);
            trackStarted = track.startsWith("正在加载");
            if (trackStarted) {
                notes.add("轨迹：" + track);
            } else {
                notes.add("轨迹未开始：" + track);
            }
        }
        if (!trackStarted && !diffStarted) {
            return String.join("；", notes);
        }
        if (!trackStarted && diffStarted) {
            // 只请求轨迹但失败时，不留下半开的 Diff 会话。
            DiffViewService.getInstance().stop(server, viewer, null, allowOthers);
            return String.join("；", notes);
        }
        Session s = new Session();
        s.viewer = viewer.getUUID();
        s.track = trackStarted;
        s.diff = diffStarted;
        s.startedAtMs = System.currentTimeMillis();
        sessions.put(s.viewer, s);
        return "镜头回看已开始（" + (s.track ? "轨迹" : "轨迹关")
                + " + " + (s.diff ? "Diff" : "Diff 关") + "）；"
                + String.join("；", notes)
                + " · 停止：/samsara camera stop";
    }

    public synchronized String stop(MinecraftServer server, ServerPlayer actor,
                                    String target, boolean allowOthers) {
        UUID uuid = resolve(server, actor, target, allowOthers);
        if (uuid == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的镜头回看"
                    : "没有该观看者的镜头回看: " + target;
        }
        String track = ViewManager.getInstance().stop(server, actor, target, allowOthers);
        String diff = DiffViewService.getInstance().stop(server, actor, target, allowOthers);
        sessions.remove(uuid);
        return "镜头回看已停止 · 轨迹：" + track + " · Diff：" + diff;
    }

    public synchronized String status(MinecraftServer server, ServerPlayer actor, String target) {
        ServerPlayer viewer = target == null || target.isBlank() ? actor
                : server.getPlayerList().getPlayerByName(target);
        if (viewer == null) {
            return "找不到观看者：" + target;
        }
        Session s = sessions.get(viewer.getUUID());
        if (s == null) {
            String track = ViewManager.getInstance().status(server, actor, target);
            String diff = DiffViewService.getInstance().status();
            return "没有进行中的镜头回看：" + track + " · " + diff;
        }
        String track = s.track
                ? ViewManager.getInstance().status(server, actor, target)
                : "已关闭（--track=off）";
        String diff = s.diff ? DiffViewService.getInstance().status() : "已关闭（--diff=off）";
        return "镜头回看：" + track + " · " + diff;
    }

    public synchronized String pause(MinecraftServer server, ServerPlayer actor,
                                     String target, boolean allowOthers) {
        String track = ViewManager.getInstance().pause(server, actor, target, allowOthers);
        String diff = DiffViewService.getInstance().setPaused(nameOf(actor, target), true);
        return "镜头已暂停 · 轨迹：" + track + " · Diff：" + diff;
    }

    public synchronized String resume(MinecraftServer server, ServerPlayer actor,
                                      String target, boolean allowOthers) {
        String track = ViewManager.getInstance().resume(server, actor, target, allowOthers);
        String diff = DiffViewService.getInstance().setPaused(nameOf(actor, target), false);
        return "镜头已继续 · 轨迹：" + track + " · Diff：" + diff;
    }

    public synchronized String seek(MinecraftServer server, ServerPlayer actor,
                                    String target, String spec, boolean allowOthers) {
        String track = ViewManager.getInstance().seek(server, actor, target, spec, allowOthers);
        String diff = DiffViewService.getInstance().seek(nameOf(actor, target), spec);
        return "镜头跳转 · 轨迹：" + track + " · Diff：" + diff;
    }

    public synchronized String speed(MinecraftServer server, ServerPlayer actor,
                                     String target, double value, boolean allowOthers) {
        String track = ViewManager.getInstance().setSpeed(server, actor, target, value, allowOthers);
        String diff = DiffViewService.getInstance().setSpeed(nameOf(actor, target), value);
        return "镜头倍速 " + value + "× · 轨迹：" + track + " · Diff：" + diff;
    }

    private static String nameOf(ServerPlayer actor, String target) {
        if (target != null && !target.isBlank()) {
            return target;
        }
        return actor == null ? null : actor.getScoreboardName();
    }

    public synchronized boolean hasSession(UUID viewer) {
        return sessions.containsKey(viewer);
    }

    public synchronized void forget(UUID viewer) {
        sessions.remove(viewer);
    }

    private UUID resolve(MinecraftServer server, ServerPlayer actor, String target,
                         boolean allowOthers) {
        if (target == null || target.isBlank()) {
            return actor == null ? null : actor.getUUID();
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(target);
        return player == null ? null : player.getUUID();
    }

    /**
     * 解析 {@code camera start} 的 greedy 输入：目标/开始时间 + 选项。
     * 相机专属选项被剥离，剩余选项原样交给 {@link ViewManager}。
     */
    public static StartRequest parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少目标或开始时间");
        }
        String[] tokens = text.split("\\s+");
        List<String> targets = new ArrayList<>();
        boolean all = false;
        String start;
        int index;
        String first = tokens[0];
        if ("all".equalsIgnoreCase(first)) {
            all = true;
            if (tokens.length < 2) {
                throw new IllegalArgumentException("all 后需要开始时间");
            }
            start = tokens[1];
            index = 2;
        } else if (isTime(first)) {
            all = true;
            start = first;
            index = 1;
        } else {
            for (String part : first.split(",")) {
                if (!part.isBlank()) {
                    targets.add(part.trim());
                }
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("缺少回看目标");
            }
            if (tokens.length < 2) {
                throw new IllegalArgumentException("目标后需要开始时间");
            }
            start = tokens[1];
            index = 2;
        }
        boolean track = true;
        boolean diff = true;
        double speed = 1.0;
        int radius = DiffViewService.DEFAULT_RADIUS;
        String dimension = null;
        List<String> trackOptions = new ArrayList<>();
        for (int i = index; i < tokens.length; i++) {
            String token = tokens[i];
            String value = null;
            String key = token;
            int eq = token.indexOf('=');
            if (eq > 0) {
                key = token.substring(0, eq);
                value = token.substring(eq + 1);
            }
            switch (key) {
                case "--track" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                    }
                    track = bool(value);
                }
                case "--diff" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                    }
                    diff = bool(value);
                }
                case "--diff-radius" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                    }
                    try {
                        radius = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--diff-radius 应为 1~32 的整数");
                    }
                    if (radius < 1 || radius > 32) {
                        throw new IllegalArgumentException("--diff-radius 应在 1~32 之间");
                    }
                }
                case "--diff-dimension", "--diff-dim" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                    }
                    dimension = value;
                }
                case "--speed" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                        trackOptions.add(token);
                    } else {
                        trackOptions.add(token);
                    }
                    trackOptions.add(value);
                    try {
                        speed = clampSpeed(Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--speed 应为 0.1~64 的数字");
                    }
                }
                default -> trackOptions.add(token);
            }
        }
        return new StartRequest(targets, all, start, String.join(" ", trackOptions),
                track, diff, speed, radius, dimension,
                diffToOf(start, trackOptions));
    }

    /** 把轨迹的 --duration 换算成 Diff 的结束时间，保证两个回看窗口一致。 */
    private static String diffToOf(String start, List<String> options) {
        String duration = null;
        for (int i = 0; i + 1 < options.size(); i++) {
            if ("--duration".equals(options.get(i))) {
                duration = options.get(i + 1);
            }
        }
        if (duration == null || duration.isBlank()) {
            return null;
        }
        Long delta = durationMs(duration);
        if (delta == null) {
            return null;
        }
        try {
            return Long.toString(ViewTime.parseAbsolute(start) + delta);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long durationMs(String text) {
        String value = text.trim().toLowerCase();
        try {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("s")) {
                return (long) (Double.parseDouble(value.substring(0, value.length() - 1)) * 1000);
            }
            if (value.endsWith("m")) {
                return (long) (Double.parseDouble(value.substring(0, value.length() - 1)) * 60_000);
            }
            if (value.endsWith("h")) {
                return (long) (Double.parseDouble(value.substring(0, value.length() - 1)) * 3_600_000);
            }
            return Long.parseLong(value) * 1000;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String next(String[] tokens, int index, String option) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException(option + " 缺少值");
        }
        return tokens[index];
    }

    private static boolean bool(String value) {
        return !"off".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)
                && !"0".equals(value);
    }

    private static double clampSpeed(double speed) {
        return Math.max(0.1, Math.min(64.0, speed));
    }

    private static boolean isTime(String token) {
        try {
            ViewTime.parseAbsolute(token);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
