package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 出生点历史（{@code <世界>/samsara/spawn-history.json}）。
 *
 * <p>世界 = 文件夹，周目 = 出生点历史中的一段。当前出生点 = 最后一条记录，
 * 也就是当前「新一轮」。不再用 {@code season.json} 的注册记录来定义周目。
 *
 * <p>追加规则：
 * <ul>
 *   <li>世界首次发现：追加 {@code initial}；</li>
 *   <li>同世界重置：追加 {@code same-world-reset}（由重置流程显式写入）；</li>
 *   <li>启动时 {@code level.dat} 出生点与最后一条距离 &gt; {@value #DETECT_THRESHOLD}：追加 {@code detected}。</li>
 * </ul>
 *
 * <p>纯数据 + 文件读写，不依赖服务端类型，便于单测。
 */
public final class SpawnHistory {
    public static final int DETECT_THRESHOLD = 16;
    public static final String FILE_NAME = "spawn-history.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int schemaVersion = 1;
    /** 是否由旧 season.json 迁移而来（只读标记，不参与流程判断）。 */
    public boolean migratedFromSeasonJson = false;
    public List<Cycle> cycles = new ArrayList<>();

    /** 一次「出生点周期」：同一世界内的一轮。 */
    public static final class Cycle {
        public int index;
        public int x;
        public int z;
        public long createdAt;
        /** initial | same-world-reset | detected | migrated | new-world */
        public String source = "";

        public Cycle() {
        }

        public Cycle(int index, int x, int z, long createdAt, String source) {
            this.index = index;
            this.x = x;
            this.z = z;
            this.createdAt = createdAt;
            this.source = source;
        }
    }

    public SpawnHistory() {
    }

    // ---- 文件 ----

    public static Path file(Path worldDir) {
        return worldDir.resolve("samsara").resolve(FILE_NAME);
    }

    public static SpawnHistory load(Path worldDir) {
        Path file = file(worldDir);
        SpawnHistory history = new SpawnHistory();
        if (Files.exists(file)) {
            try {
                SpawnHistory loaded = GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), SpawnHistory.class);
                if (loaded != null) {
                    history = loaded;
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] {} 解析失败，使用空出生点历史: {}",
                        file, e.getMessage());
            }
        }
        if (history.cycles == null) {
            history.cycles = new ArrayList<>();
        }
        history.normalize();
        return history;
    }

    public void save(Path worldDir) {
        Path file = file(worldDir);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] {} 写入失败: {}", file, e.getMessage());
        }
    }

    // ---- 查询/追加 ----

    public boolean isEmpty() {
        return cycles.isEmpty();
    }

    public int size() {
        return cycles.size();
    }

    public Cycle last() {
        return cycles.isEmpty() ? null : cycles.get(cycles.size() - 1);
    }

    public int currentIndex() {
        Cycle last = last();
        return last == null ? -1 : last.index;
    }

    public List<int[]> centers() {
        List<int[]> out = new ArrayList<>(cycles.size());
        for (Cycle c : cycles) {
            out.add(new int[]{c.x, c.z});
        }
        return out;
    }

    /** 首次发现世界：没有历史时写入 initial；已有历史则只在偏移过大时追加 detected。 */
    public Cycle ensureInitial(int x, int z, long now) {
        if (cycles.isEmpty()) {
            return append(x, z, "initial", now);
        }
        return detect(x, z, now);
    }

    /**
     * 启动检测：当前 level.dat 出生点与最后一条历史相距 &gt; {@value #DETECT_THRESHOLD} 时追加。
     * 返回新追加的记录；未追加返回 null。
     */
    public Cycle detect(int x, int z, long now) {
        Cycle last = last();
        if (last == null) {
            return append(x, z, "initial", now);
        }
        if (distance(last.x, last.z, x, z) > DETECT_THRESHOLD) {
            return append(x, z, "detected", now);
        }
        return null;
    }

    public Cycle append(int x, int z, String source, long now) {
        Cycle c = new Cycle(cycles.size(), x, z, now, source == null ? "" : source);
        cycles.add(c);
        return c;
    }

    /** 规范化 index：连续、从 0 开始。只修复乱序/重复，不改变先后顺序。 */
    public void normalize() {
        for (int i = 0; i < cycles.size(); i++) {
            cycles.get(i).index = i;
        }
    }

    /**
     * 从旧 season.json 迁移：按开始时间排序，把每个已开始周目的出生中心写为只读历史。
     * 旧记录没有出生中心时退回世界出生点。
     */
    public static SpawnHistory fromSeasons(SeasonData data, int spawnX, int spawnZ, long now) {
        SpawnHistory history = new SpawnHistory();
        List<Season> started = new ArrayList<>();
        if (data != null && data.seasons != null) {
            for (Season s : data.seasons) {
                if (s != null && s.startTime != null) {
                    started.add(s);
                }
            }
        }
        started.sort(Comparator.comparingLong((Season s) -> s.startTime)
                .thenComparingInt(s -> s.id));
        for (Season s : started) {
            if (s.spawnCenter != null) {
                SpawnHistory.Cycle last = history.last();
                if (last != null && distance(last.x, last.z, s.spawnCenter.x,
                        s.spawnCenter.z) <= DETECT_THRESHOLD) {
                    continue; // 旧调试数据可能重复记录同一出生中心，迁移时合并
                }
                history.append(s.spawnCenter.x, s.spawnCenter.z, "migrated", now);
            }
        }
        if (!started.isEmpty()) {
            history.migratedFromSeasonJson = true;
        }
        history.ensureInitial(spawnX, spawnZ, now);
        return history;
    }

    public static int distance(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return (int) Math.round(Math.hypot(dx, dz));
    }

    public String summaryText() {
        Cycle last = last();
        if (last == null) {
            return "出生点历史：空";
        }
        return "当前第 " + (last.index + 1) + " 轮 · 出生点 (" + last.x + ", " + last.z
                + ") · 历史 " + cycles.size() + " 条";
    }

    public String historyText() {
        if (cycles.isEmpty()) {
            return "出生点历史：空（世界启动后会自动写入 initial）";
        }
        StringBuilder sb = new StringBuilder("出生点历史（旧 → 新）：");
        for (Cycle c : cycles) {
            sb.append("\n").append(c.index + 1).append(". (").append(c.x).append(", ")
                    .append(c.z).append(") · ").append(c.source);
            if (c.createdAt > 0) {
                sb.append(" · ").append(Instant.ofEpochMilli(c.createdAt));
            }
            if (c == cycles.get(cycles.size() - 1)) {
                sb.append("  ← 当前轮");
            }
        }
        return sb.toString();
    }
}
