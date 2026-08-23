package com.shenqianye.samsara.activity;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活跃度（活动度）—— <b>主数据源 activity.log</b> 的读写与聚合。
 * <p>
 * <b>activity.log 格式</b>（一行一条，`epoch_ms 玩家名 事件`）：玩家上下线事件实时追加，
 * 服务端重启不丢，离线由 {@code tools/analyze.py} 复读。
 * <pre>
 * 1787200000000 Alice JOIN
 * 1787200030000 Alice LEAVE
 * </pre>
 * <p>
 * 指标：
 * <ul>
 *   <li>周目维度：近 7/14/30 天活跃玩家数、日均在线（总在线时长/记录窗口天数）、峰值在线（事件重放取 max）、记录玩家数；</li>
 *   <li>玩家维度：活跃（≤7 天）、回归（8~30 天，且总分在线时长 &gt; 阈值；不足标注）、流失（31~60 天）、沉睡（&gt;60 天）；</li>
 *   <li>死服判定（建议，可配置）：连续 ≥deadDays 天 0 人上线 且 近 30 天活跃 ≤deadActiveMax → 判"周目已死"，建议开新周目。</li>
 * </ul>
 * <p>
 * 单例：JOIN/DISCONNECT 钩子与各命令共享同一实例。
 * <p>
 * 配置项（Settings，`/samsara debug config`）：{@code deadDays}（默认 14）、{@code deadActiveMax}（默认 2）。
 */
public class ActivityManager {
    private static ActivityManager instance;
    private final Path file;
    private final Path rotateFile;
    /** 日志体积阈值（行）：超过后滚动到 activity.1.log 并开新文件（防无限膨胀） */
    private static final long ROTATE_LINES = 100_000L;

    /** 回归分类的总在线时长阈值（秒）——低于此判定在线过短并标注 */
    private static final long RETURN_MIN_ONLINE_SEC = 600;
    /** 活跃窗口（天）：7；回归窗口取 8~30；流失 31~60；沉睡 &gt;60 */
    private static final long ACTIVE_DAYS = 7;
    private static final long RETURN_MAX_DAYS = 30;
    private static final long LOST_MAX_DAYS = 60;
    /** 命令默认"近期"窗口（天） */
    public static final int DEFAULT_RECENT_DAYS = 30;
    private static final long DAY_MS = 86_400_000L;
    /** 死服判定默认值（可在 Settings 调）：连续 N 天 0 人上线 + 近 30 天活跃 ≤M → 死服 */
    public static final int DEFAULT_DEAD_DAYS = 14;
    public static final int DEFAULT_DEAD_ACTIVE_MAX = 2;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日志条目（注意：仅 JOIN 上线事件决定"最后登录"） */
    private static final String EV_JOIN = "JOIN";
    private static final String EV_LEAVE = "LEAVE";

    private ActivityManager() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("activity.log");
        rotateFile = dir.resolve("activity.1.log");
    }

    public static synchronized ActivityManager getInstance() {
        if (instance == null) {
            instance = new ActivityManager();
        }
        return instance;
    }

    /** activity.log 绝对路径（命令展示用） */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    // ---- 事件写入（JOIN/DISCONNECT 钩子调用） ----

    public void onJoin(String name) {
        append(name, EV_JOIN);
    }

    public void onLeave(String name) {
        append(name, EV_LEAVE);
    }

    private synchronized void append(String name, String event) {
        try {
            // 行数阈值旋转：activity.log 超过阈值 → 滚为 activity.1.log（覆盖）并开新文件
            if (Files.exists(file) && Files.readAllLines(file, StandardCharsets.UTF_8).size() >= ROTATE_LINES) {
                Files.move(file, rotateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String line = System.currentTimeMillis() + " " + name + " " + event + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] activity.log 写入失败: {}", e.getMessage());
        }
    }

    /** 读全部日志条目（先读滚动档 activity.1.log，再读当前档；含部分残行防御） */
    private List<Entry> readEntries() {
        List<Entry> out = new ArrayList<>();
        try {
            readFileInto(rotateFile, out);
            readFileInto(file, out);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] activity.log 读取失败: {}", e.getMessage());
        }
        return out;
    }

    private void readFileInto(Path f, List<Entry> out) throws IOException {
        if (!Files.exists(f)) {
            return;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] p = t.split("\\s+");
            if (p.length < 2) {
                continue;
            }
            long ms;
            try {
                ms = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new Entry(ms, p[1], p.length >= 3 ? p[2] : EV_JOIN));
        }
    }

    /** 单个玩家的聚合结果 */
    public static class PlayerStat {
        public final String name;
        /** 最后上线（最后一次 JOIN） */
        public long lastJoin;
        /** 首次出现 */
        public long firstSeen;
        /** 登录次数（JOIN 数） */
        public int sessions;
        /** 总在线时长（pair 累加 + 未闭合会话截至 now） */
        public long totalOnlineMs;

        PlayerStat(String name) {
            this.name = name;
        }

        /** 距最近登录的天数（未登录为很大值） */
        public long daysAgo(long now) {
            return lastJoin <= 0 ? Long.MAX_VALUE : (now - lastJoin) / DAY_MS;
        }

        public long totalOnlineSec() {
            return totalOnlineMs / 1000;
        }
    }

    /** 单条日志 */
    private static class Entry {
        final long ms;
        final String name;
        final String ev;

        Entry(long ms, String name, String ev) {
            this.ms = ms;
            this.name = name;
            this.ev = ev;
        }
    }

    /**
     * 聚合全部玩家状态。会话配对：JOIN 开、LEAVE 关；连续 JOIN 取首个（防宕机重复计数）；
     * 未闭合会话截至 now。最后一列事件缺省视为 JOIN（兼容旧格式，无事件列时全按 JOIN）。
     */
    public Map<String, PlayerStat> aggregate(long now) {
        List<Entry> entries = readEntries();
        Map<String, PlayerStat> map = new HashMap<>();
        // 玩家聚合：firstSeen / lastJoin / sessions
        for (Entry e : entries) {
            PlayerStat st = map.computeIfAbsent(e.name, PlayerStat::new);
            if (st.firstSeen == 0 || e.ms < st.firstSeen) {
                st.firstSeen = e.ms;
            }
            if (EV_JOIN.equals(e.ev)) {
                st.sessions++;
                if (e.ms > st.lastJoin) {
                    st.lastJoin = e.ms;
                }
            }
        }
        // 会话时长配对：按每个玩家的 JOIN/LEAVE 顺序累加
        Map<String, List<Entry>> byPlayer = new HashMap<>();
        for (Entry e : entries) {
            byPlayer.computeIfAbsent(e.name, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Entry>> en : byPlayer.entrySet()) {
            PlayerStat st = map.get(en.getKey());
            if (st == null) {
                continue;
            }
            boolean open = false;
            long openAt = 0;
            for (Entry e : en.getValue()) {
                if (EV_JOIN.equals(e.ev)) {
                    if (!open) {
                        open = true;
                        openAt = e.ms;
                    }
                    // 双 JOIN 无 LEAVE：保留首个会话
                } else {
                    if (open) {
                        st.totalOnlineMs += e.ms - openAt;
                        open = false;
                    }
                }
            }
            if (open) {
                st.totalOnlineMs += Math.max(0, now - openAt);
            }
        }
        return map;
    }

    // ---- 指标文本（命令呈现） ----

    /** 近 N 天活跃玩家列表（按最后登录倒序） */
    public String recentText(int days) {
        long now = System.currentTimeMillis();
        Map<String, PlayerStat> stats = aggregate(now);
        long cutoff = now - days * DAY_MS;
        List<PlayerStat> recent = new ArrayList<>();
        for (PlayerStat st : stats.values()) {
            if (st.lastJoin >= cutoff) {
                recent.add(st);
            }
        }
        recent.sort((a, b) -> Long.compare(b.lastJoin, a.lastJoin));
        if (recent.isEmpty()) {
            return "近 " + days + " 天无玩家登录（activity.log 开始累计后记录）"
                    + "\n日志位置: " + configPath();
        }
        StringBuilder sb = new StringBuilder("近 " + days + " 天活跃玩家（" + recent.size() + " 人，按最后登录倒序）：\n");
        for (PlayerStat st : recent) {
            sb.append("  ").append(st.name).append(" · 最后登录 ").append(fmtTs(st.lastJoin))
                    .append("（").append(fmtAgo(st.lastJoin, now)).append("）· 共 ").append(st.sessions).append(" 次登录\n");
        }
        sb.append("共 ").append(recent.size()).append(" 人 / 全部记录 ").append(stats.size()).append(" 人");
        return sb.toString().trim();
    }

    /** 周目维度 + 玩家维度统计 */
    public String statsText() {
        long now = System.currentTimeMillis();
        Map<String, PlayerStat> stats = aggregate(now);
        if (stats.isEmpty()) {
            return "尚无活动记录（activity.log 自部署后开始累计）\n日志位置: " + configPath();
        }
        long first = Long.MAX_VALUE;
        for (PlayerStat st : stats.values()) {
            first = Math.min(first, st.firstSeen);
        }
        long windowDays = Math.max(1, (now - first) / DAY_MS + 1);

        // 周目维度
        int a7 = countActive(stats, now, ACTIVE_DAYS);
        int a14 = countActive(stats, now, 14);
        int a30 = countActive(stats, now, 30);
        long totalOnline = 0;
        long lastJoin = 0;
        for (PlayerStat st : stats.values()) {
            totalOnline += st.totalOnlineMs;
            lastJoin = Math.max(lastJoin, st.lastJoin);
        }
        int peak = peakConcurrent(now);
        int newIn30 = 0;
        for (PlayerStat st : stats.values()) {
            if (st.firstSeen >= now - 30 * DAY_MS) {
                newIn30++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("周目维度（记录窗口 ").append(windowDays).append(" 天，自 ").append(fmtTs(first)).append("）：\n");
        sb.append("  最近 7 / 14 / 30 天活跃: ").append(a7).append(" / ").append(a14).append(" / ").append(a30).append(" 人\n");
        sb.append("  日均在线: ").append(fmtDurMs(totalOnline / windowDays)).append(" · 峰值在线: ").append(peak).append(" 人\n");
        sb.append("  近 30 天新见玩家: ").append(newIn30).append(" 人（记录起累积，共 ").append(stats.size()).append(" 人）\n");
        sb.append("玩家维度分类（按最后登录）：\n");
        List<PlayerStat> sorted = new ArrayList<>(stats.values());
        sorted.sort((a, b) -> Long.compare(b.lastJoin, a.lastJoin));
        int[] catCount = new int[4];
        for (PlayerStat st : sorted) {
            String cat = classify(st, now);
            catCount[indexOf(cat)]++;
            sb.append("  ").append(st.name).append(" · ").append(cat)
                    .append(" · 最后登录 ").append(fmtAgo(st.lastJoin, now))
                    .append(" · 在线 ").append(fmtDurMs(st.totalOnlineMs))
                    .append(" · ").append(st.sessions).append(" 次\n");
        }
        sb.append("分类汇总: 活跃 ").append(catCount[0]).append(" · 回归 ").append(catCount[1])
                .append(" · 流失 ").append(catCount[2]).append(" · 沉睡 ").append(catCount[3]);
        return sb.toString();
    }

    private int countActive(Map<String, PlayerStat> stats, long now, long days) {
        long cutoff = now - days * DAY_MS;
        int n = 0;
        for (PlayerStat st : stats.values()) {
            if (st.lastJoin >= cutoff) {
                n++;
            }
        }
        return n;
    }

    /** 峰值在线：事件重放（JOIN +1 / LEAVE -1，钳 0） */
    private int peakConcurrent(long now) {
        List<Entry> list = new ArrayList<>(readEntries());
        list.sort((a, b) -> Long.compare(a.ms, b.ms));
        int cur = 0;
        int peak = 0;
        for (Entry e : list) {
            if (EV_JOIN.equals(e.ev)) {
                cur++;
            } else {
                cur = Math.max(0, cur - 1);
            }
            peak = Math.max(peak, cur);
        }
        return peak;
    }

    /** 死服判定（建议阈值可配置 Settings.deadDays / deadActiveMax） */
    public String deadText() {
        var cfg = settings();
        long now = System.currentTimeMillis();
        Map<String, PlayerStat> stats = aggregate(now);
        if (stats.isEmpty()) {
            return "尚无活动记录（activity.log 自部署后开始累计），暂无法判定死服\n日志位置: " + configPath();
        }
        long lastJoin = 0;
        for (PlayerStat st : stats.values()) {
            lastJoin = Math.max(lastJoin, st.lastJoin);
        }
        long daysSince = (now - lastJoin) / DAY_MS;
        int active30 = countActive(stats, now, 30);
        boolean c1 = daysSince >= cfg.deadDays;
        boolean c2 = active30 <= cfg.deadActiveMax;
        StringBuilder sb = new StringBuilder();
        sb.append("死服判定（建议，阈值 ").append(cfg.deadDays).append(" 天无上线 + 近30天活跃 ≤ ").append(cfg.deadActiveMax).append("）：\n");
        sb.append("  最近登录: ").append(fmtTs(lastJoin)).append("（").append(fmtAgo(lastJoin, now)).append("）")
                .append(" · 近 30 天活跃 ").append(active30).append(" 人\n");
        if (c1 && c2) {
            sb.append("  ⚠ 判定「周目已死」：已连续 ").append(daysSince).append(" 天无人上线且近 30 天活跃仅 ")
                    .append(active30).append(" 人 → 建议开新周目（最终由管理员决定）");
        } else if (!c1 && !c2) {
            sb.append("  判定「周目存活」：近 ").append(daysSince).append(" 天仍有登录（< ").append(cfg.deadDays)
                    .append(" 天连续无人上线），平均活跃 ").append(active30).append(" 人");
        } else if (c1) {
            sb.append("  判定「接近死服」：已连续 ").append(daysSince).append(" 天无人上线，但近 30 天活跃仍有 ")
                    .append(active30).append(" 人（> ").append(cfg.deadActiveMax).append("）");
        } else {
            sb.append("  判定「接近死服」：近 30 天活跃仅 ").append(active30).append(" 人，但最近一次登录在 ")
                    .append(fmtAgo(lastJoin, now)).append("（连续无人上线 < ").append(cfg.deadDays).append(" 天）");
        }
        return sb.toString();
    }

    /** 紧凑单行（/samsara season status 附加用） */
    public String compactLine() {
        long now = System.currentTimeMillis();
        Map<String, PlayerStat> stats = aggregate(now);
        if (stats.isEmpty()) {
            return "活跃度: 暂无记录（起累计）";
        }
        int a7 = countActive(stats, now, ACTIVE_DAYS);
        int a14 = countActive(stats, now, 14);
        int a30 = countActive(stats, now, 30);
        long lastJoin = 0;
        long total = 0;
        for (PlayerStat st : stats.values()) {
            lastJoin = Math.max(lastJoin, st.lastJoin);
            total += st.totalOnlineMs;
        }
        long daysSince = (now - lastJoin) / DAY_MS;
        int active30 = a30;
        var cfg = settings();
        boolean dead = daysSince >= cfg.deadDays && active30 <= cfg.deadActiveMax;
        return "活跃度: 7/14/30天 " + a7 + "/" + a14 + "/" + a30 + " 人 · 峰值 " + peakConcurrent(now) + " · 日均 "
                + fmtDurMs(total / Math.max(1, windowDays(stats))) + (dead ? " · ⚠ 死服判定，建议开新周目"
                : " · 近" + Math.min(daysSince, 999) + "天有登录");
    }

    private long windowDays(Map<String, PlayerStat> stats) {
        long first = Long.MAX_VALUE;
        for (PlayerStat st : stats.values()) {
            first = Math.min(first, st.firstSeen);
        }
        return first == Long.MAX_VALUE ? 1 : Math.max(1, (System.currentTimeMillis() - first) / DAY_MS + 1);
    }

    // ---- 玩家分类 ----

    /**
     * 分类（阈值取固定合理值，见类注释）：
     * 活跃 ≤7 天；回归 8~30 天（在线总时长不足 ≥10 分钟则标注）；流失 31~60 天；沉睡 &gt;60 天。
     */
    public String classify(PlayerStat st, long now) {
        long days = st.daysAgo(now);
        if (days <= ACTIVE_DAYS) {
            return "活跃";
        }
        if (days <= RETURN_MAX_DAYS) {
            return st.totalOnlineSec() >= RETURN_MIN_ONLINE_SEC ? "回归" : "回归(在线过短)";
        }
        if (days <= LOST_MAX_DAYS) {
            return "流失";
        }
        return "沉睡";
    }

    private int indexOf(String cat) {
        if (cat.startsWith("活跃")) {
            return 0;
        }
        if (cat.startsWith("回归")) {
            return 1;
        }
        if (cat.startsWith("流失")) {
            return 2;
        }
        return 3;
    }

    // ---- 配置（Settings 委托，SeasonManager.settings()） ----

    private com.shenqianye.samsara.season.SeasonData.Settings settings() {
        return SeasonManager.getInstance().settings();
    }

    // ---- 格式化工具 ----

    private static String fmtTs(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(TS_FMT);
    }

    private static String fmtAgo(long ms, long now) {
        long diff = now - ms;
        long d = diff / DAY_MS;
        if (d > 0) {
            return d + " 天前";
        }
        long h = diff / 3_600_000L;
        if (h > 0) {
            return h + " 小时前";
        }
        long m = diff / 60_000L;
        if (m > 0) {
            return m + " 分钟前";
        }
        return "刚刚";
    }

    private static String fmtDurMs(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) {
            return h + " 小时 " + m + " 分";
        }
        if (m > 0) {
            return m + " 分";
        }
        return s + " 秒";
    }

    // ---- 调试/测试支持 ----

    /** 模拟死服判定参数（不写文件，仅构造缩略判断用于 debug simulate） */
    public String simulateText(long daysSince, int active30) {
        var cfg = settings();
        boolean c1 = daysSince >= cfg.deadDays;
        boolean c2 = active30 <= cfg.deadActiveMax;
        return "模拟死服判定（假设最后登录 " + daysSince + " 天前、近 30 天活跃 " + active30 + " 人；阈值 "
                + cfg.deadDays + " 天 + ≤" + cfg.deadActiveMax + "）：\n"
                + (c1 && c2
                ? "  ⚠ 判定「周目已死」→ 建议开新周目"
                : "  判定「周目存活」/「接近死服」：条件一(连续≥" + cfg.deadDays + "天无人上线)="
                + c1 + " · 条件二(近30天活跃≤" + cfg.deadActiveMax + ")=" + c2);
    }

    /** 清空 activity.log（debug clear，需二次确认） */
    public synchronized String clear() {
        try {
            Files.deleteIfExists(file);
            return "已清空 activity.log（" + configPath() + "）——活跃度将从空记录重新累计";
        } catch (IOException e) {
            return "清空失败: " + e.getMessage();
        }
    }

    /** 近 30 天活跃玩家数（simulate 缺省参数用） */
    public int active30Count() {
        long now = System.currentTimeMillis();
        return countActive(aggregate(now), now, 30);
    }

    /** 日志行数（debug 概要用） */
    public int lineCount() {
        return readEntries().size();
    }
}
