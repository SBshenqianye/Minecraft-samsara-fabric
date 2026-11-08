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
        boolean fixedFocus;
        int chunkX;
        int chunkZ;
        int radius;
        long startedAtMs;
    }

    public record StartRequest(List<String> targets, boolean all, String start,
                               String trackOptions, boolean track, boolean diff,
                               double speed, int diffRadius, String diffDimension,
                               String diffTo, boolean fixedFocus, int chunkX, int chunkZ) {
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
            String diff;
            if (request.fixedFocus()) {
                int cx = request.chunkX() == Integer.MIN_VALUE
                        ? viewer.chunkPosition().x() : request.chunkX();
                int cz = request.chunkZ() == Integer.MIN_VALUE
                        ? viewer.chunkPosition().z() : request.chunkZ();
                diff = DiffViewService.getInstance().startGlobalFixed(server, viewer,
                        request.diffDimension(), request.start(), request.diffTo(),
                        request.speed(), cx, cz, request.diffRadius());
            } else {
                diff = DiffViewService.getInstance().startGlobal(server, viewer,
                        request.diffDimension(), request.start(), request.diffTo(),
                        request.speed(), request.diffRadius());
            }
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
        if (request.track() && !trackStarted) {
            // 任一侧失败时默认回滚另一方，恢复到启动前状态。
            if (diffStarted) {
                DiffViewService.getInstance().stop(server, viewer, null, allowOthers);
            }
            return String.join("；", notes) + "；已回滚，未留下回看会话";
        }
        if (request.diff() && !diffStarted) {
            if (trackStarted) {
                ViewManager.getInstance().stop(server, viewer, null, allowOthers);
            }
            return String.join("；", notes) + "；已回滚，未留下回看会话";
        }
        Session s = new Session();
        s.viewer = viewer.getUUID();
        s.track = trackStarted;
        s.diff = diffStarted;
        s.fixedFocus = request.fixedFocus();
        s.chunkX = request.chunkX() == Integer.MIN_VALUE
                ? viewer.chunkPosition().x() : request.chunkX();
        s.chunkZ = request.chunkZ() == Integer.MIN_VALUE
                ? viewer.chunkPosition().z() : request.chunkZ();
        s.radius = request.diffRadius();
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
        // 即使 camera 会话记录已丢失，也尝试清理两边，保证 stop 幂等。
        String track = ViewManager.getInstance().stop(server, actor, target, allowOthers);
        String diff = DiffViewService.getInstance().stop(server, actor, target, allowOthers);
        boolean removed = uuid == null ? sessions.isEmpty() : sessions.remove(uuid) != null;
        if (!removed && !track.contains("已停止") && !diff.contains("已停止")
                && !track.contains("已结束") && !diff.contains("已结束")) {
            return target == null || target.isBlank() ? "你当前没有进行中的镜头回看"
                    : "没有该观看者的镜头回看: " + target;
        }
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
                : "已关闭（--no-track）";
        String diff = s.diff
                ? DiffViewService.getInstance().status(nameOf(actor, target))
                : "已关闭（--no-diff）";
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

    /** 每 tick：固定焦点模式下，人偶离开焦点区块范围时隐藏，回来再显示。 */
    public synchronized void tick(MinecraftServer server) {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session s : new ArrayList<>(sessions.values())) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
            if (viewer == null) {
                continue;
            }
            if (!s.fixedFocus || !s.track) {
                continue;
            }
            int dx = viewer.chunkPosition().x() - s.chunkX;
            int dz = viewer.chunkPosition().z() - s.chunkZ;
            boolean inside = Math.hypot(dx, dz) <= Math.max(0, s.radius) + 0.5;
            ViewManager.getInstance().setMannequinVisible(s.viewer, inside);
        }
    }

    public synchronized boolean hasSession(UUID viewer) {
        return sessions.containsKey(viewer);
    }

    public synchronized void forget(UUID viewer) {
        sessions.remove(viewer);
    }

    public synchronized void shutdown(MinecraftServer server) {
        sessions.clear();
    }

    /** ViewManager 异步加载失败时回调：回滚同一 camera 会话里的 Diff。 */
    public synchronized void onViewFailed(MinecraftServer server, UUID viewer) {
        Session s = viewer == null ? null : sessions.get(viewer);
        if (s == null) {
            return;
        }
        if (s.diff) {
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            DiffViewService.getInstance().stop(server, player, null, true);
        }
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
        boolean fixedFocus = false;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        double speed = 1.0;
        Integer radiusValue = null;
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
                case "--no-track" -> track = false;
                case "--no-diff" -> diff = false;
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
                case "--diff-radius", "--radius" -> {
                    if (value == null) {
                        value = next(tokens, ++i, key);
                    }
                    try {
                        radiusValue = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--radius 应为 0~32 的整数");
                    }
                    if (radiusValue < 0 || radiusValue > 32) {
                        throw new IllegalArgumentException("--radius 应在 0~32 之间");
                    }
                }
                case "--chunk" -> {
                    fixedFocus = true;
                    chunkX = intValue(tokens, ++i, "--chunk x");
                    chunkZ = intValue(tokens, ++i, "--chunk z");
                }
                case "--fixed" -> fixedFocus = true;
                case "--diff-dimension", "--diff-dim", "--dim" -> {
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
        int radius = radiusValue != null ? radiusValue
                : (fixedFocus ? 0 : DiffViewService.DEFAULT_RADIUS);
        return new StartRequest(targets, all, start, String.join(" ", trackOptions),
                track, diff, speed, radius, dimension,
                diffToOf(start, trackOptions), fixedFocus, chunkX, chunkZ);
    }

    private static int intValue(String[] tokens, int index, String label) {
        String value = next(tokens, index, label);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " 应为整数");
        }
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
