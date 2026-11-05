package com.shenqianye.samsara.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.DiffStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 片段收藏（F-103）与保留保护区间（F-104）。
 *
 * <p>磁盘文件：{@code <trackRoot>/clips.json}，与 {@code cutoffs.json} 同级。
 * 收藏只影响保留扫描时 {@code .bin} 的降采样密度，不读写 {@code diff/}，
 * 也不改变任何样本的 {@code timeMs}、顺序或回看/倒流的方块时间轴。
 */
public final class TrackClips {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    public static final class Data {
        public int version = 1;
        public List<Entry> clips = new ArrayList<>();
    }

    public static final class Entry {
        public String id;
        public String world;
        public int cycle;
        public String uuid;
        public long fromMs;
        public long toMs;
        public String label = "";
        public String createdBy;
        public long createdAt;

        public Entry() {
        }

        public Entry(String id, String world, int cycle, UUID uuid, long fromMs, long toMs,
                     String label, String createdBy, long createdAt) {
            this.id = id;
            this.world = world;
            this.cycle = cycle;
            this.uuid = uuid.toString();
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.label = label == null ? "" : label;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
        }

        public UUID owner() {
            try {
                return UUID.fromString(uuid);
            } catch (Exception e) {
                return null;
            }
        }

        public long durationMs() {
            return Math.max(0, toMs - fromMs);
        }

        public boolean valid() {
            return id != null && !id.isBlank() && world != null && uuid != null
                    && fromMs > 0 && toMs > fromMs && owner() != null
                    && createdBy != null && !createdBy.isBlank();
        }
    }

    /** 保护区间（闭区间；用于 TrackStore.decimate）。 */
    public record Range(long fromMs, long toMs) {
        public boolean intersects(long from, long to) {
            return from <= toMs && to >= fromMs;
        }
    }

    private final Path file;
    private final Data data = new Data();

    public TrackClips(Path file) {
        this.file = file;
        load();
    }

    /** 文件缺失/损坏时从空表开始；坏条目跳过。 */
    public synchronized void load() {
        data.clips.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Data back = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (back != null && back.clips != null) {
                for (Entry e : back.clips) {
                    if (e != null && e.valid()) {
                        data.clips.add(e);
                    }
                }
                data.version = back.version;
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] clips.json 解析失败，按空表继续: {}", e.getMessage());
        }
    }

    public synchronized Entry add(Entry entry) {
        if (entry == null || !entry.valid()) {
            return null;
        }
        Entry copy = entry;
        if (copy.id == null || copy.id.isBlank()) {
            copy.id = newId();
        }
        data.clips.add(copy);
        save();
        return copy;
    }

    public synchronized boolean remove(String id) {
        if (id == null) {
            return false;
        }
        boolean removed = data.clips.removeIf(e -> id.equalsIgnoreCase(e.id));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Entry find(String id) {
        if (id == null) {
            return null;
        }
        for (Entry e : data.clips) {
            if (id.equalsIgnoreCase(e.id)) {
                return e;
            }
        }
        return null;
    }

    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>(data.clips);
        out.sort(Comparator.comparingLong(e -> e.fromMs));
        return out;
    }

    public synchronized List<Entry> forTarget(String world, int cycle, UUID uuid) {
        String w = DiffStore.sanitizeWorldKey(world);
        String id = uuid == null ? "" : uuid.toString();
        List<Entry> out = new ArrayList<>();
        for (Entry e : data.clips) {
            if (e.cycle == cycle && id.equals(e.uuid) && w.equals(e.world)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(e -> e.fromMs));
        return out;
    }

    public synchronized List<Entry> forCreator(String creator) {
        List<Entry> out = new ArrayList<>();
        if (creator == null) {
            return out;
        }
        for (Entry e : data.clips) {
            if (creator.equalsIgnoreCase(e.createdBy)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(e -> e.fromMs));
        return out;
    }

    public synchronized int countByCreator(String creator) {
        return forCreator(creator).size();
    }

    /** 某玩家在某周目的全部保护区间（跨文件/跨段）。 */
    public synchronized List<Range> protectedRanges(String world, int cycle, UUID uuid) {
        List<Range> out = new ArrayList<>();
        for (Entry e : forTarget(world, cycle, uuid)) {
            out.add(new Range(e.fromMs, e.toMs));
        }
        return out;
    }

    public synchronized List<Entry> targetsIn(int cycle) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : data.clips) {
            if (e.cycle == cycle) {
                out.add(e);
            }
        }
        return out;
    }

    /** 生成 5 位短 id（冲突时重试）。 */
    public synchronized String newId() {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder sb = new StringBuilder(5);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 5; i++) {
                sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
            }
            String id = sb.toString();
            if (find(id) == null) {
                return id;
            }
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] clips.json 写入失败: {}", e.getMessage());
        }
    }
}
