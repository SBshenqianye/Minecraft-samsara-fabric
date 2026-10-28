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
import java.util.List;
import java.util.UUID;

/**
 * 「捕捉截断点」（capture cutoff）：玩家在回放中捕捉（攻击/交互）人偶后，
 * 该玩家在<b>该周目</b>从捕捉时刻之后的轨迹作废，不再参与本次及以后回放。
 * <p>
 * 磁盘记录<b>保留</b>（只做回放过滤，不删除 .bin），标记写入
 * {@code track/cutoffs.json}。截断点按「世界 + 周目 + 玩家 UUID」三元组隔离：
 * 捕捉只影响该玩家该周目的后续回放，其他玩家、其他周目、其他世界不受影响。
 * <p>
 * 同一玩家在同一周目被再次捕捉时取<b>更早</b>的时刻（回放永远不会越过第一个截断点）。
 */
public final class TrackCuts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 磁盘结构（version 便于以后扩容） */
    public static final class Data {
        public int version = 1;
        public List<Entry> cutoffs = new ArrayList<>();
    }

    public static final class Entry {
        public String world;
        public int cycle;
        public String uuid;
        public long captureMs;

        public Entry() {
        }

        public Entry(String world, int cycle, UUID uuid, long captureMs) {
            this.world = world;
            this.cycle = cycle;
            this.uuid = uuid.toString();
            this.captureMs = captureMs;
        }
    }

    private final Path file;
    private final Data data = new Data();

    public TrackCuts(Path file) {
        this.file = file;
        load();
    }

    /** 读取 cutoffs.json；文件不存在/损坏时从空表开始（不阻断正常记录与回放） */
    public synchronized void load() {
        data.cutoffs.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Data back = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (back != null && back.cutoffs != null) {
                for (Entry e : back.cutoffs) {
                    if (e != null && e.uuid != null && e.world != null) {
                        data.cutoffs.add(e);
                    }
                }
                data.version = back.version;
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] cutoffs.json 解析失败，按空表继续: {}", e.getMessage());
        }
    }

    /** 记录一次捕捉；同一玩家/周目/世界取更早的时刻。 */
    public synchronized void capture(String world, int cycle, UUID uuid, long captureMs) {
        String w = DiffStore.sanitizeWorldKey(world);
        Entry found = find(w, cycle, uuid);
        if (found == null) {
            data.cutoffs.add(new Entry(w, cycle, uuid, captureMs));
        } else {
            found.captureMs = Math.min(found.captureMs, captureMs);
        }
        save();
    }

    /** 截断时刻（epoch ms）；没有捕捉记录返回 null */
    public synchronized Long cutoff(String world, int cycle, UUID uuid) {
        Entry e = find(DiffStore.sanitizeWorldKey(world), cycle, uuid);
        return e == null ? null : e.captureMs;
    }

    /** 全部截断点（状态展示用） */
    public synchronized List<Entry> all() {
        return new ArrayList<>(data.cutoffs);
    }

    private Entry find(String world, int cycle, UUID uuid) {
        String id = uuid.toString();
        for (Entry e : data.cutoffs) {
            if (e.cycle == cycle && id.equals(e.uuid) && world.equals(e.world)) {
                return e;
            }
        }
        return null;
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
            Samsara.LOGGER.warn("[周目管理 samsara] cutoffs.json 写入失败: {}", e.getMessage());
        }
    }
}
