package com.shenqianye.samsara.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.storage.StoragePaths;
import com.shenqianye.samsara.track.view.ViewTime;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 玩家聊天记录（F-109）。
 *
 * <p>只记录 {@code ServerMessageEvents.CHAT_MESSAGE} 里的玩家发言，不解析服务端日志，
 * 也不记录命令反馈、加入/离开、死亡等系统信息。
 *
 * <pre>
 * chat/cycle&lt;周目id&gt;/&lt;YYYY-MM-DD&gt;.jsonl
 * {"timeMs":1761667200000,"uuid":"...","name":"Alice","dimension":"minecraft:overworld",
 *  "x":55.0,"y":64.0,"z":3.0,"message":"hello"}
 * </pre>
 *
 * <p>默认永久保留（{@code track.chatRetentionDays=0}）；配置正数时只按日期文件清理。
 * 记录、回看模拟、倒流留言都只读这一份数据，互不依赖轨迹 {@code .evt}。
 */
public final class ChatLogStore {
    private static final Gson GSON = new GsonBuilder().create();
    /** 与 .evt 聊天 payload 的上限保持一致。 */
    public static final int MAX_MESSAGE_CHARS = 256;

    private ChatLogStore() {
    }

    /** 一条聊天记录。 */
    public record Entry(long timeMs, UUID uuid, String name, String dimension,
                        double x, double y, double z, String message) {
    }

    // ---- 路径 ----

    /** 聊天记录根：{@code <worldDataRoot>/chat}。 */
    public static Path root() {
        return StoragePaths.get().worldDataRoot().resolve("chat");
    }

    public static Path cycleDir(Path root, int cycleId) {
        return root.resolve(TrackLayout.cycleDirName(cycleId));
    }

    public static Path fileFor(Path root, int cycleId, LocalDate day) {
        return cycleDir(root, cycleId).resolve(day + ".jsonl");
    }

    // ---- 写 ----

    /** 记录一条玩家发言；空消息、超长消息直接丢弃。 */
    public static void record(ServerPlayer sender, String text) {
        if (sender == null || text == null) {
            return;
        }
        String message = text.strip();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_CHARS) {
            return;
        }
        try {
            int cycle = SeasonManager.getInstance().current() == null
                    ? 0 : SeasonManager.getInstance().current().id;
            long now = System.currentTimeMillis();
            Entry entry = new Entry(now, sender.getUUID(), sender.getScoreboardName(),
                    sender.level().dimension().identifier().toString(),
                    sender.getX(), sender.getY(), sender.getZ(), message);
            append(root(), cycle, entry);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 聊天记录写入失败 {}: {}",
                    sender.getUUID(), e.getMessage());
        }
    }

    /** 直接追加到指定根/周目（导入、测试用）。 */
    public static synchronized void append(Path root, int cycleId, Entry entry) {
        if (entry == null) {
            return;
        }
        LocalDate day = ViewTime.dayOf(entry.timeMs());
        Path file = fileFor(root, cycleId, day);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(GSON.toJson(entry));
                out.newLine();
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 聊天记录写入失败 {}: {}", file, e.getMessage());
        }
    }

    // ---- 读 ----

    /** 读取周目内时间窗口的聊天，可按身份组过滤（uuids 为空/null = 不过滤）。 */
    public static List<Entry> readRange(int cycleId, long fromMs, long toMs,
                                        Collection<UUID> uuids) {
        return readRange(root(), cycleId, fromMs, toMs, uuids);
    }

    public static List<Entry> readRange(Path root, int cycleId, long fromMs, long toMs,
                                        Collection<UUID> uuids) {
        List<Entry> out = new ArrayList<>();
        if (toMs < fromMs) {
            return out;
        }
        LocalDate day = ViewTime.dayOf(fromMs);
        LocalDate lastDay = ViewTime.dayOf(toMs);
        for (LocalDate d = day; !d.isAfter(lastDay); d = d.plusDays(1)) {
            Path file = fileFor(root, cycleId, d);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Entry entry = parse(line);
                    if (entry == null) {
                        continue;
                    }
                    if (entry.timeMs() < fromMs || entry.timeMs() > toMs) {
                        continue;
                    }
                    if (uuids != null && !uuids.isEmpty() && !uuids.contains(entry.uuid())) {
                        continue;
                    }
                    out.add(entry);
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 聊天记录读取失败 {}: {}", file, e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong(Entry::timeMs));
        return out;
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            Entry entry = GSON.fromJson(line, Entry.class);
            if (entry == null || entry.uuid() == null || entry.message() == null
                    || entry.timeMs() <= 0 || entry.dimension() == null) {
                return null;
            }
            return entry;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- 保留 ----

    /** 按配置清理超过保留期的按天文件；返回删除的文件数。0 = 永久保留。 */
    public static int purgeExpired(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        Path root = root();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int removed = 0;
        try (Stream<Path> cycles = Files.list(root)) {
            for (Path cycleDir : (Iterable<Path>) cycles::iterator) {
                if (!Files.isDirectory(cycleDir)) {
                    continue;
                }
                try (Stream<Path> days = Files.list(cycleDir)) {
                    for (Path file : (Iterable<Path>) days::iterator) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".jsonl")) {
                            continue;
                        }
                        String dayText = name.substring(0, name.length() - 6);
                        LocalDate day;
                        try {
                            day = LocalDate.parse(dayText);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (day.isBefore(cutoff)) {
                            try {
                                if (Files.deleteIfExists(file)) {
                                    removed++;
                                }
                            } catch (IOException e) {
                                Samsara.LOGGER.warn("[周目管理 samsara] 聊天记录清理失败 {}: {}",
                                        file, e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 聊天记录清理失败: {}", e.getMessage());
        }
        if (removed > 0) {
            Samsara.LOGGER.info("[周目管理 samsara] 聊天记录清理：删除 {} 个超过 {} 天的日期文件",
                    removed, retentionDays);
        }
        return removed;
    }

    /** 当前配置的保留天数（供调试/状态展示）。 */
    public static int configuredRetentionDays() {
        return CommandConfig.getInstance().trackChatRetentionDays();
    }
}
