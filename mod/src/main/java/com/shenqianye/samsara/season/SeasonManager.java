package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 周目状态机（M1）：season.json 持久化 + create/start/end 流转。
 * <p>
 * 状态机（设计文档 §3.4）：
 * PENDING --(start)--> ACTIVE --(end)--> ENDED --(archive, M 后续)--> ARCHIVED
 * <p>
 * 单例：所有命令/功能共享同一实例与内存状态，避免多实例不同步
 * （曾出现 /season 与 /samsara debug 各持实例导致 debug 读到旧状态）。
 */
public class SeasonManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SeasonManager instance;
    private final Path file;
    private SeasonData data;

    private SeasonManager() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("season.json");
        load();
    }

    public static synchronized SeasonManager getInstance() {
        if (instance == null) {
            instance = new SeasonManager();
        }
        return instance;
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SeasonData.class);
                if (data == null) {
                    data = new SeasonData();
                }
            } catch (Exception e) {
                throw new RuntimeException("season.json 解析失败（可删除该文件后重启重建）: " + e.getMessage(), e);
            }
        } else {
            data = new SeasonData();
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("season.json 写入失败: " + e.getMessage(), e);
        }
    }

    // ---- 状态机操作 ----

    /** /season create <name> [mode] */
    public String create(String name, String mode) {
        if (currentPending() != null) {
            return "存在未开始的周目 #" + currentPending().id + "「" + currentPending().name + "」，请先 start 或 end";
        }
        if (!"same-world".equals(mode) && !"new-world".equals(mode)) {
            return "模式必须为 same-world 或 new-world";
        }
        Season s = new Season();
        s.id = data.seasons.isEmpty() ? 1 : data.seasons.get(data.seasons.size() - 1).id + 1;
        s.name = name;
        s.mode = mode;
        data.seasons.add(s);
        save();
        return "已创建周目 #" + s.id + "「" + name + "」（" + mode + "，PENDING）";
    }

    /** /season start —— M2 将在此处接入分组出生/传送、世界栈（§4/§14） */
    public String start() {
        Season s = currentPending();
        if (s == null) {
            return "没有待开始的周目（先 /season create）";
        }
        s.state = Season.State.ACTIVE;
        s.startTime = Instant.now().toEpochMilli();
        data.currentSeasonId = s.id;
        save();
        return "周目 #" + s.id + "「" + s.name + "」开始（ACTIVE）。M2 起将在此执行分组出生/传送";
    }

    /** /season end */
    public String end() {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有进行中的周目";
        }
        s.state = Season.State.ENDED;
        s.endTime = Instant.now().toEpochMilli();
        save();
        return "周目 #" + s.id + "「" + s.name + "」已结束（ENDED）";
    }

    // ---- 查询 ----

    public Season currentPending() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.PENDING) {
                return s;
            }
        }
        return null;
    }

    public Season current() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.ACTIVE || s.state == Season.State.PENDING) {
                return s;
            }
        }
        return data.seasons.isEmpty() ? null : data.seasons.get(data.seasons.size() - 1);
    }

    /** 周目总数（概要展示用） */
    public int seasonCount() {
        return data.seasons.size();
    }

    public String statusText() {
        return statusText(0);
    }

    /**
     * 周目列表文本。
     *
     * @param page 页码（&gt;0 时仅显示该页，每页 8 条；&lt;=0 显示全部）
     */
    public String statusText(int page) {
        final int pageSize = 8;
        StringBuilder sb = new StringBuilder();
        if (data.seasons.isEmpty()) {
            sb.append("尚无周目，使用 /season create <名称> 创建");
        } else {
            int total = data.seasons.size();
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            if (page > 0 && page > pages) {
                page = pages;
            }
            int from = page > 0 ? (page - 1) * pageSize : 0;
            int to = page > 0 ? Math.min(from + pageSize, total) : total;
            for (int i = from; i < to; i++) {
                Season s = data.seasons.get(i);
                sb.append("#").append(s.id).append("「").append(s.name).append("」 ")
                        .append(s.state).append(" · ").append(s.mode);
                if (s.startTime != null) {
                    sb.append(" · 始于 ").append(fmt(s.startTime));
                }
                if (s.endTime != null) {
                    sb.append(" · 止于 ").append(fmt(s.endTime));
                }
                sb.append("\n");
            }
            if (page > 0 && pages > 1) {
                sb.append("--- 第 ").append(page).append("/").append(pages).append(" 页，共 ").append(total).append(" 条 ---\n");
            }
            Season c = current();
            if (c != null) {
                sb.append("当前：#").append(c.id).append("「").append(c.name).append("」 ").append(c.state);
            }
        }
        if (!data.worldStack.isEmpty()) {
            sb.append("\n世界栈：").append(String.join(" → ", data.worldStack));
        }
        return sb.toString().trim();
    }

    private static String fmt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ---- 调试/测试支持（/samsara debug，OWNER 权限） ----

    /** 重新从磁盘加载 season.json（测试 load 路径） */
    public String reload() {
        load();
        return "已从磁盘重新加载 " + file.getFileName();
    }

    /** 清空全部周目数据，重置为初始状态 */
    public String reset() {
        data = new SeasonData();
        save();
        return "已清空全部周目数据（重置为初始状态）";
    }

    /** season.json 原始内容（pretty JSON） */
    public String rawJson() {
        return GSON.toJson(data);
    }

    /**
     * season.json 绝对路径（normalize 去除 '.\' 前缀）。
     */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    /**
     * 批量创建"历史周目"（直接 ENDED，每天一个），用于快速构造多周目状态列表
     * 与 ID 递增的测试数据；绕过 PENDING 独占限制。
     */
    public String createBulk(String baseName, String mode, int count) {
        if (!"same-world".equals(mode) && !"new-world".equals(mode)) {
            return "模式必须为 same-world 或 new-world";
        }
        List<String> created = new ArrayList<>();
        long now = Instant.now().toEpochMilli();
        for (int i = 1; i <= count; i++) {
            Season s = new Season();
            s.id = data.seasons.isEmpty() ? 1 : data.seasons.get(data.seasons.size() - 1).id + 1;
            s.name = count == 1 ? baseName : baseName + "-" + i;
            s.mode = mode;
            s.state = Season.State.ENDED;
            s.startTime = now - (long) (count - i) * 86_400_000L;   // 每天一个
            s.endTime = s.startTime + 3_600_000L;
            data.seasons.add(s);
            created.add("#" + s.id + "「" + s.name + "」");
        }
        save();
        return "已创建 " + count + " 个历史周目（ENDED）：" + String.join(", ", created);
    }

    /** 直接设置周目时间戳（测试时间显示/排序）；value 支持 now 或 epoch 毫秒 */
    public String setTime(int id, String field, String value) {
        Season s = findById(id);
        if (s == null) {
            return "未找到周目 #" + id;
        }
        long ms;
        if ("now".equals(value)) {
            ms = System.currentTimeMillis();
        } else {
            try {
                ms = Long.parseLong(value);
            } catch (NumberFormatException e) {
                return "时间值必须为 now 或 epoch 毫秒数";
            }
        }
        switch (field) {
            case "start" -> s.startTime = ms;
            case "end" -> s.endTime = ms;
            default -> {
                return "字段必须为 start 或 end";
            }
        }
        save();
        String result = "已设置周目 #" + id + " 的 " + field + " = " + fmt(ms);
        // 警告：start/end 时间倒挂（debug 允许构造边界，但提醒）
        if (s.startTime != null && s.endTime != null && s.startTime > s.endTime) {
            result += "\n⚠ 注意：该周目的开始时间晚于结束时间（时间倒挂），属非正常状态";
        }
        return result;
    }

    private Season findById(int id) {
        for (Season s : data.seasons) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }
}
