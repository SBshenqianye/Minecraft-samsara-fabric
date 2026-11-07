package com.shenqianye.samsara.web;

import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.view.ViewTrackReader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轨迹回看的只读 Web 入口：列出最近可回看的玩家与时段，并生成游戏内命令。
 *
 * <p>不改变轨迹格式，也不重写回放算法；真正的回看在游戏内由 ViewManager 执行。
 */
public final class TrackWebService {
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final MinecraftServer server;

    public TrackWebService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> list(Map<String, String> query) {
        if (server == null) return error(503, "服务器未启动");
        int days = clamp(intValue(query.get("days"), 7), 1, 90);
        int limit = clamp(intValue(query.get("limit"), 200), 1, 500);
        long now = System.currentTimeMillis();
        long from = now - days * 86_400_000L;
        int cycleId = currentCycle();
        String playerRef = query.getOrDefault("player", "").trim();
        Map<UUID, String> names = names();
        List<Path> roots = TrackRecorder.getInstance().trackRoots();
        List<Map<String, Object>> tracks = new ArrayList<>();

        if (!playerRef.isBlank()) {
            UUID uuid = TrackRecorder.resolveUuid(server, playerRef);
            if (uuid == null) return error(404, "找不到玩家：" + playerRef);
            List<ViewTrackReader.Available> available = ViewTrackReader.available(
                    roots, List.of(uuid), (owner, cycle) -> null);
            for (ViewTrackReader.Available row : available) {
                if (row.lastMs() < from || row.firstMs() > now) continue;
                tracks.add(track(row, uuid, names.getOrDefault(uuid, playerRef)));
            }
        } else {
            for (ViewTrackReader.AvailablePlayer row
                    : ViewTrackReader.availablePlayers(roots, cycleId, from, now)) {
                Map<String, Object> track = new LinkedHashMap<>();
                UUID uuid = row.uuid();
                String name = names.getOrDefault(uuid, uuid.toString());
                track.put("uuid", uuid.toString());
                track.put("name", name);
                track.put("cycleId", cycleId);
                track.put("firstMs", row.firstMs());
                track.put("lastMs", row.lastMs());
                track.put("firstAt", TIME_FMT.format(Instant.ofEpochMilli(row.firstMs())));
                track.put("lastAt", TIME_FMT.format(Instant.ofEpochMilli(row.lastMs())));
                track.put("subject", name);
                track.put("command", command(name, row.firstMs()));
                tracks.add(track);
            }
        }

        tracks.sort(Comparator.comparingLong(
                (Map<String, Object> row) -> number(row.get("lastMs"))).reversed());
        if (tracks.size() > limit) tracks = new ArrayList<>(tracks.subList(0, limit));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cycleId", cycleId);
        out.put("from", from);
        out.put("to", now);
        out.put("days", days);
        out.put("count", tracks.size());
        out.put("tracks", tracks);
        out.put("notes", List.of(
                "轨迹回放仍在服务器内执行；复制命令后在游戏内运行。",
                "只读接口，不删除/改写轨迹，也不启动回放。",
                "群组回放可用 /samsara track view all <开始时间>。"));
        return out;
    }

    private Map<String, Object> track(ViewTrackReader.Available row, UUID uuid, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uuid", uuid.toString());
        out.put("name", name);
        out.put("cycleId", row.cycleId());
        out.put("day", row.day() == null ? "" : row.day().toString());
        out.put("firstMs", row.firstMs());
        out.put("lastMs", row.lastMs());
        out.put("firstAt", TIME_FMT.format(Instant.ofEpochMilli(row.firstMs())));
        out.put("lastAt", TIME_FMT.format(Instant.ofEpochMilli(row.lastMs())));
        out.put("samples", row.samples());
        out.put("truncated", row.truncated());
        out.put("cutoffApplied", row.cutoffApplied());
        out.put("error", row.error() == null ? "" : row.error());
        out.put("subject", name);
        out.put("command", command(name, row.firstMs()));
        if (row.file() != null) {
            out.put("fileName", row.file().getFileName().toString());
            try {
                out.put("bytes", Files.size(row.file()));
            } catch (Exception ignored) {
                out.put("bytes", 0L);
            }
        }
        return out;
    }

    private static String command(String subject, long firstMs) {
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(firstMs));
        return "/samsara track view " + subject + " " + time + " --speed 4 --duration 30m";
    }

    private int currentCycle() {
        Season current = SeasonManager.getInstance().current();
        return current == null ? 0 : current.id;
    }

    private Map<UUID, String> names() {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (PlayerRegistry.RegPlayer player : PlayerRegistry.getInstance().read(server)) {
            out.put(player.uuid, player.name);
        }
        return out;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intValue(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", message);
        return out;
    }
}
