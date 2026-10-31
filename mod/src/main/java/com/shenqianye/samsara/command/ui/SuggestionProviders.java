package com.shenqianye.samsara.command.ui;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.shenqianye.samsara.permission.PermissionConfig;
import com.shenqianye.samsara.permission.PermissionLevel;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.view.ViewTime;
import com.shenqianye.samsara.track.view.ViewTrackReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Tab 补全统一入口（Brigadier {@link SuggestionProvider}）。
 *
 * <h3>分层</h3>
 * <ul>
 *   <li><b>纯数据</b>：{@code players(server)} / {@code knownPlayers(server)} / {@code cycles(ids)} /
 *       {@code viewOptions()} / {@code durations()} / {@code permissionLevels()} /
 *       {@code commandPaths()} / {@code seasonModes()} / {@code merge(...)} —— 不依赖 Brigadier，可直接单测；</li>
 *   <li><b>Brigadier 适配</b>：所有 {@code xxxProvider()} 方法返回 {@link SuggestionProvider}，
 *       内部分别调用上面的纯数据方法；</li>
 *   <li><b>贪心参数</b>：{@code viewGreedyProvider()} / {@code permInputProvider()} 需要按“当前 token”
 *       而不是“整段 remaining”做前缀过滤，见 {@link #token(String, char, Collection)}。</li>
 * </ul>
 *
 * <h3>Brigadier 的两个坑</h3>
 * <ol>
 *   <li><b>remaining 必须小写再比较</b>：{@link SuggestionsBuilder#getRemaining()} 保留原始大小写，
 *       直接 {@code startsWith} 会让 {@code --Spectator} 之类永远补不出来；</li>
 *   <li><b>greedyString 不能用 {@code builder.suggest(...)}</b>：Brigadier 用整段 remaining 做前缀过滤，
 *       还会把整段 remaining 作为替换范围（按 Tab 会把已输入的开始时间吃掉）。
 *       这种参数一律用 {@link #offerToken} 手工构造 {@link Suggestions}，只替换当前 token。</li>
 * </ol>
 *
 * <h3>性能</h3>
 * 补全回调在服务端主线程执行，<b>不能</b>每次按键都读磁盘：
 * 在线玩家直接取内存列表；已知玩家/周目/回看时段走短 TTL 缓存（见各 {@code TTL_*} 常量）。
 */
public final class SuggestionProviders {
    /** 已知玩家缓存 TTL（usercache/playerdata 只按此周期刷新，不按键刷新） */
    public static final long TTL_KNOWN_PLAYERS_MS = 60_000L;
    /** 周目目录缓存 TTL */
    public static final long TTL_CYCLES_MS = 10_000L;
    /** 回看开始时刻缓存 TTL */
    public static final long TTL_VIEW_TIMES_MS = 15_000L;

    /** 回看可选参数（与 {@code ViewOptions.parse} 支持的键一致） */
    private static final List<String> VIEW_OPTIONS = SuggestionCatalog.viewOptions();

    private static final List<String> DURATIONS = SuggestionCatalog.durations();

    private static final List<String> SPEEDS = SuggestionCatalog.speeds();

    private static final List<String> SEASON_MODES = SuggestionCatalog.seasonModes();

    private static final List<String> FAKE_PLAYER_HINTS = SuggestionCatalog.fakePlayerHints();

    // ---- 缓存（只缓存内存/短周期 IO 的结果，避免每次按键读盘） ----

    private static volatile List<String> cachedKnownPlayers = List.of();
    /** 已知玩家名（小写）→ UUID，用于补全 hover；与 {@link #cachedKnownPlayers} 同周期刷新。 */
    private static volatile Map<String, UUID> cachedKnownPlayerUuids = Map.of();
    private static volatile long cachedKnownPlayersAt;
    private static volatile List<String> cachedCycles = List.of();
    private static volatile long cachedCyclesAt;
    private static final Map<String, ViewTimeCache> VIEW_TIME_CACHE = new LinkedHashMap<>();

    private record ViewTimeCache(long at, List<String> times) {
    }

    private SuggestionProviders() {
    }

    // ==================== 纯数据 ====================

    /** 在线玩家名（内存，不读盘）。 */
    public static List<String> players(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.add(player.getScoreboardName());
        }
        return CompletionSupport.dedupe(out);
    }

    /**
     * 在线 + 已知/离线玩家名：在线玩家 + fakeplayers 注入池 + {@code PlayerRegistry.read}
     * （usercache/playerdata）。后两者按 {@link #TTL_KNOWN_PLAYERS_MS} 缓存，
     * 不会在每次按键时重新解析 usercache。
     *
     * <p>只有 {@code nameKnown} 的条目才会进入候选：仅剩 {@code players/data/<uuid>.dat}
     * 的占位名（{@code 玩家-xxxxxxxx}）不是真实玩家 ID，点了也执行不了。
     */
    public static List<String> knownPlayers(MinecraftServer server) {
        List<String> out = new ArrayList<>(players(server));
        try {
            out.addAll(PlayerRegistry.getInstance().injectedNames());
        } catch (Exception ignored) {
            // 注入池只影响补全，读取失败不阻断
        }
        refreshKnownPlayers(server);
        out.addAll(cachedKnownPlayers);
        return CompletionSupport.dedupe(out);
    }

    /** 按 TTL 刷新已知玩家缓存（名字 + name→uuid）。 */
    private static void refreshKnownPlayers(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - cachedKnownPlayersAt <= TTL_KNOWN_PLAYERS_MS) {
            return;
        }
        try {
            List<String> names = new ArrayList<>();
            Map<String, UUID> uuids = new LinkedHashMap<>();
            for (PlayerRegistry.RegPlayer p : PlayerRegistry.getInstance().read(server)) {
                if (p.nameKnown && p.name != null && !p.name.isBlank()) {
                    names.add(p.name);
                    uuids.putIfAbsent(p.name.toLowerCase(Locale.ROOT), p.uuid);
                }
            }
            cachedKnownPlayers = List.copyOf(names);
            cachedKnownPlayerUuids = Map.copyOf(uuids);
        } catch (Exception ignored) {
            // usercache 读取失败：退回在线 + 注入池
        } finally {
            cachedKnownPlayersAt = now;
        }
    }

    /**
     * 解析玩家名对应的 UUID（在线 → 注入池 → usercache/playerdata 缓存），
     * 用于补全 hover；找不到时返回 null。
     */
    public static UUID playerUuid(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            if (online != null) {
                return online.getUUID();
            }
        }
        try {
            for (String injected : PlayerRegistry.getInstance().injectedNames()) {
                if (injected.equalsIgnoreCase(name)) {
                    return PlayerRegistry.offlineUuid(injected);
                }
            }
        } catch (Exception ignored) {
            // 注入池不可用时继续查缓存
        }
        refreshKnownPlayers(server);
        return cachedKnownPlayerUuids.get(name.toLowerCase(Locale.ROOT));
    }

    /** 玩家名候选的 hover：保留“点击填入”提示，再补一行 UUID（不覆盖原信息）。 */
    public static String playerTooltip(MinecraftServer server, String name) {
        return playerTooltip(server, name, "玩家");
    }

    /**
     * 玩家名候选的 hover，带角色说明。
     *
     * @param role 该参数里玩家是什么对象，例如“监控目标”“观看者”“接收者”；为空时用“玩家”
     */
    public static String playerTooltip(MinecraftServer server, String name, String role) {
        UUID uuid = playerUuid(server, name);
        String label = role == null || role.isBlank() ? "玩家" : role;
        String base = "点击填入" + label + "：" + name;
        return uuid == null ? base : base + "\nUUID：" + uuid;
    }

    /** 在已有 hover 文案后补一行 UUID；不是已知玩家时原样返回。 */
    private static String appendUuid(MinecraftServer server, String name, String base) {
        UUID uuid = playerUuid(server, name);
        return uuid == null ? base : base + "\nUUID：" + uuid;
    }

    /** 周目 id → {@code cycleN}，保留传入顺序并去重。 */
    public static List<String> cycles(Collection<Integer> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (Integer id : ids) {
            if (id != null) {
                out.add("cycle" + id);
            }
        }
        return CompletionSupport.dedupe(out);
    }

    /** 当前世界轨迹目录下的全部周目（短 TTL 缓存）。 */
    public static List<String> cycles() {
        long now = System.currentTimeMillis();
        if (now - cachedCyclesAt > TTL_CYCLES_MS) {
            try {
                cachedCycles = List.copyOf(cycles(TrackRecorder.getInstance().cycleIds()));
            } catch (Throwable ignored) {
                // 轨迹目录/周目管理器未初始化（例如单元测试）：补全为空
            } finally {
                cachedCyclesAt = now;
            }
        }
        return cachedCycles;
    }

    /**
     * 当前目标玩家的可回看开始时刻（{@code yyyy-MM-ddTHH:mm:ss}，可直接粘进命令）。
     * 复用 {@link ViewTrackReader#available}，结果按玩家名缓存 {@link #TTL_VIEW_TIMES_MS}。
     */
    public static List<String> viewStartTimes(MinecraftServer server, String targetName) {
        if (server == null || targetName == null || targetName.isBlank()) {
            return List.of();
        }
        String key = targetName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (VIEW_TIME_CACHE) {
            ViewTimeCache cached = VIEW_TIME_CACHE.get(key);
            if (cached != null && now - cached.at() <= TTL_VIEW_TIMES_MS) {
                return cached.times();
            }
        }
        List<String> times;
        try {
            times = loadViewStartTimes(server, targetName);
        } catch (Exception e) {
            times = List.of();
        }
        synchronized (VIEW_TIME_CACHE) {
            VIEW_TIME_CACHE.put(key, new ViewTimeCache(now, times));
            while (VIEW_TIME_CACHE.size() > 32) {
                String oldest = VIEW_TIME_CACHE.keySet().iterator().next();
                VIEW_TIME_CACHE.remove(oldest);
            }
        }
        return times;
    }

    private static List<String> loadViewStartTimes(MinecraftServer server, String targetName) {
        UUID uuid = TrackRecorder.resolveUuid(server, targetName);
        if (uuid == null) {
            return List.of();
        }
        TrackRecorder recorder = TrackRecorder.getInstance();
        Path root = recorder.worldRoot();
        if (root == null) {
            return List.of();
        }
        String worldKey = recorder.worldKey();
        String key = worldKey == null
                ? com.shenqianye.samsara.rewind.DiffStore.sanitizeWorldKey("world")
                : worldKey;
        recorder.cuts().load();
        List<ViewTrackReader.Available> available = ViewTrackReader.available(root, uuid,
                cycle -> recorder.cuts().cutoff(key, cycle, uuid));
        List<String> out = new ArrayList<>();
        for (ViewTrackReader.Available a : available) {
            if (a.error() != null) {
                continue;
            }
            out.add(ViewTime.format(a.firstMs()).replace(' ', 'T'));
            if (out.size() >= 30) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 回看选项全集（纯数据）。 */
    public static List<String> viewOptions() {
        return VIEW_OPTIONS;
    }

    /** 时长候选（纯数据）。 */
    public static List<String> durations() {
        return DURATIONS;
    }

    /** 权限等级候选（纯数据）。 */
    public static List<String> permissionLevels() {
        return List.of(PermissionLevel.USER.name().toLowerCase(Locale.ROOT),
                PermissionLevel.OP.name().toLowerCase(Locale.ROOT),
                PermissionLevel.OWNER.name().toLowerCase(Locale.ROOT),
                PermissionLevel.CONFIG.name().toLowerCase(Locale.ROOT));
    }

    /** 权限配置里的命令路径（内存字典；单元测试等无 Fabric 环境时退回内置默认表）。 */
    public static List<String> commandPaths() {
        try {
            return List.copyOf(PermissionConfig.getInstance().getAllEntries().keySet());
        } catch (Throwable ignored) {
            return PermissionConfig.defaultCommandPaths();
        }
    }

    /** 周目创建模式（纯数据）。 */
    public static List<String> seasonModes() {
        return SEASON_MODES;
    }

    /** 合并多个候选列表：去重、保留首次出现顺序。 */
    @SafeVarargs
    public static List<String> merge(Collection<String>... lists) {
        List<String> out = new ArrayList<>();
        if (lists == null) {
            return out;
        }
        for (Collection<String> list : lists) {
            if (list != null) {
                out.addAll(list);
            }
        }
        return CompletionSupport.dedupe(out);
    }

    /** 不区分大小写的前缀过滤，保留候选原顺序（委托给纯工具层）。 */
    public static List<String> filter(String prefix, Collection<String> candidates) {
        return CompletionSupport.filter(prefix, candidates);
    }

    // ==================== 贪心参数的 token 逻辑 ====================

    /**
     * 贪心参数的“当前 token”补全结果。
     *
     * @param offset     当前 token 在 remaining 中的起始下标（替换范围从它开始）
     * @param token      当前 token 原文（未小写）
     * @param candidates 已按前缀过滤的候选
     */
    public record TokenSuggestions(int offset, String token, List<String> candidates) {
    }

    /**
     * 取最后一个分隔符之后的 token，并按其前缀过滤候选。
     *
     * @param separator 空白参数传 {@code ' '}（任何空白都算分隔）；列表参数传 {@code ','}
     */
    public static TokenSuggestions token(String remaining, char separator, Collection<String> candidates) {
        String input = remaining == null ? "" : remaining;
        int offset = CompletionSupport.lastSeparator(input, separator) + 1;
        String token = input.substring(offset);
        return new TokenSuggestions(offset, token, filter(token, candidates));
    }

    /**
     * 回看 greedyString 的补全核心（纯函数，便于单测）：
     * <ol>
     *   <li>还没输入 {@code --} → 补开始时间；输入完开始时间（尾随空格）→ 补选项；</li>
     *   <li>当前 token 以 {@code --} 开头 → 补选项；</li>
     *   <li>{@code --cycle} 后 → 补 {@code cycleN}；{@code --duration} 后 → 补时长；</li>
     *   <li>{@code --speed} 后 → 补倍速；{@code --follow} 后 → 补 on/off；</li>
     *   <li>支持 {@code --cycle=cy} 这种内联写法（只替换等号后的值）。</li>
     * </ol>
     */
    public static TokenSuggestions viewInput(String remaining, Collection<String> startTimes) {
        return viewInput(remaining, startTimes, cycles());
    }

    /**
     * 回看 greedyString 的补全核心（纯函数，便于单测）：
     * <ol>
     *   <li>还没输入 {@code --} → 补开始时间；输入完开始时间（尾随空格）→ 补选项；</li>
     *   <li>当前 token 以 {@code --} 开头 → 补选项；</li>
     *   <li>{@code --cycle} 后 → 补 {@code cycleN}；{@code --duration} 后 → 补时长；</li>
     *   <li>{@code --speed} 后 → 补倍速；{@code --follow} 后 → 补 on/off；</li>
     *   <li>支持 {@code --cycle=cy} 这种内联写法（只替换等号后的值）。</li>
     * </ol>
     *
     * @param cycleCandidates 周目候选（显式传入，便于单测；命令侧传 {@link #cycles()}）
     */
    public static TokenSuggestions viewInput(String remaining, Collection<String> startTimes,
                                             Collection<String> cycleCandidates) {
        String input = remaining == null ? "" : remaining;
        int offset = CompletionSupport.lastSeparator(input, ' ') + 1;
        String token = input.substring(offset);
        String before = input.substring(0, offset).trim();

        // 内联 key=value：只补值部分
        int eq = token.indexOf('=');
        if (token.startsWith("--") && eq > 2) {
            String key = token.substring(0, eq);
            String value = token.substring(eq + 1);
            List<String> values = inlineValues(key, cycleCandidates);
            if (values != null) {
                return new TokenSuggestions(offset + eq + 1, value, filter(value, values));
            }
        }

        // 当前 token 是选项
        if (token.startsWith("--")) {
            return new TokenSuggestions(offset, token, filter(token, VIEW_OPTIONS));
        }

        boolean optionArea = before.contains("--");
        if (!optionArea) {
            // 还没开始写选项
            if (!before.isEmpty() && Character.isWhitespace(input.charAt(input.length() - 1))) {
                // 开始时间已输入完 → 进入选项
                return new TokenSuggestions(input.length(), "", VIEW_OPTIONS);
            }
            return new TokenSuggestions(0, token, filter(token, startTimes));
        }

        // 选项区：看上一个 token 是不是“需要值”的键
        String[] tokens = before.isEmpty() ? new String[0] : before.split("\\s+");
        String prev = tokens.length == 0 ? "" : tokens[tokens.length - 1];
        boolean trailingSpace = input.isEmpty()
                || Character.isWhitespace(input.charAt(input.length() - 1));
        if (trailingSpace) {
            List<String> values = valuesFor(prev, cycleCandidates);
            if (values != null) {
                return new TokenSuggestions(input.length(), "", values);
            }
            return new TokenSuggestions(input.length(), "", VIEW_OPTIONS);
        }
        List<String> values = valuesFor(prev, cycleCandidates);
        if (values != null) {
            return new TokenSuggestions(offset, token, filter(token, values));
        }
        return new TokenSuggestions(offset, token, filter(token, VIEW_OPTIONS));
    }

    private static List<String> inlineValues(String key, Collection<String> cycleCandidates) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "--cycle" -> List.copyOf(cycleCandidates == null ? List.<String>of() : cycleCandidates);
            case "--duration" -> DURATIONS;
            case "--speed" -> SPEEDS;
            case "--follow", "--aim", "--aim-pitch" -> List.of("on", "off");
            default -> null;
        };
    }

    private static List<String> valuesFor(String previousToken, Collection<String> cycleCandidates) {
        return switch (previousToken.toLowerCase(Locale.ROOT)) {
            case "--cycle" -> List.copyOf(cycleCandidates == null ? List.<String>of() : cycleCandidates);
            case "--duration" -> DURATIONS;
            case "--speed" -> SPEEDS;
            case "--follow", "--aim", "--aim-pitch" -> List.of("on", "off");
            default -> null;
        };
    }

    // ==================== Brigadier 适配 ====================

    /** 在线玩家（hover 带 UUID） */
    public static SuggestionProvider<CommandSourceStack> playersProvider() {
        return playersProvider("玩家");
    }

    /** 在线玩家，hover 用指定角色说明（如“观看者”“接收者”“监控目标”）。 */
    public static SuggestionProvider<CommandSourceStack> playersProvider(String role) {
        return (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            return CompletionSupport.offer(builder, players(server),
                    s -> playerTooltip(server, s, role));
        };
    }

    /** 在线 + 已知/离线玩家（短 TTL 缓存；hover 带 UUID） */
    public static SuggestionProvider<CommandSourceStack> knownPlayersProvider() {
        return knownPlayersProvider("玩家");
    }

    /** 在线 + 已知/离线玩家，hover 用指定角色说明。 */
    public static SuggestionProvider<CommandSourceStack> knownPlayersProvider(String role) {
        return (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            return CompletionSupport.offer(builder, knownPlayers(server),
                    s -> playerTooltip(server, s, role));
        };
    }

    /** {@code cycleN} */
    public static SuggestionProvider<CommandSourceStack> cyclesProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, cycles());
    }

    /** {@code --speed} 等倍速候选 */
    public static SuggestionProvider<CommandSourceStack> speedProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, SPEEDS, s -> "倍速 " + s + "×");
    }

    /** 时长候选 */
    public static SuggestionProvider<CommandSourceStack> durationsProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, DURATIONS, SuggestionCatalog::durationHint);
    }

    /** 权限等级（user/op/owner/config） */
    public static SuggestionProvider<CommandSourceStack> permissionLevelsProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, permissionLevels(),
                s -> "权限等级 " + s + "（config 仅控制台/配置文件）");
    }

    /** 权限配置里的命令路径 */
    public static SuggestionProvider<CommandSourceStack> commandPathProvider() {
        return (ctx, builder) -> offerWholeRemaining(builder,
                filter(builder.getRemaining().trim(), commandPaths()),
                s -> "/samsara " + s);
    }

    /** 周目创建模式 */
    public static SuggestionProvider<CommandSourceStack> seasonModesProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, seasonModes(),
                s -> "same-world：同一存档开新周目；new-world：新世界（当前仅切换出生点）");
    }

    /** true / false */
    public static SuggestionProvider<CommandSourceStack> boolProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, List.of("true", "false"));
    }

    /** 回看 greedyString：开始时间 / 选项 / cycle / duration / speed */
    public static SuggestionProvider<CommandSourceStack> viewGreedyProvider() {
        return (ctx, builder) -> {
            List<String> startTimes = List.of();
            try {
                String player = ctx.getArgument("player", String.class);
                startTimes = viewStartTimes(ctx.getSource().getServer(), player);
            } catch (Exception ignored) {
                // 没解析到目标玩家（例如 debug 入口参数名不同）：只补选项/周目
            }
            TokenSuggestions token = viewInput(builder.getRemaining(), startTimes);
            return offerToken(builder, token, SuggestionProviders::viewTooltip);
        };
    }

    /** 回看开始时刻（普通 word 参数用，不含空格） */
    public static SuggestionProvider<CommandSourceStack> viewStartTimesProvider() {
        return (ctx, builder) -> {
            List<String> times = List.of();
            try {
                String player = ctx.getArgument("player", String.class);
                times = viewStartTimes(ctx.getSource().getServer(), player);
            } catch (Exception ignored) {
                // 无 player 参数时留空
            }
            return CompletionSupport.offer(builder, times, s -> "从 " + s.replace('T', ' ') + " 开始回看");
        };
    }

    /** fakeplayers：clear / 数量 / 已知玩家名 */
    public static SuggestionProvider<CommandSourceStack> fakePlayersProvider() {
        return (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            List<String> candidates = merge(FAKE_PLAYER_HINTS,
                    knownPlayers(server));
            TokenSuggestions token = token(builder.getRemaining(), ',', candidates);
            return offerToken(builder, token, s -> appendUuid(server, s, switch (s) {
                case "clear" -> "清空注入池";
                case "1", "3", "5", "10", "20" -> "生成 TestPlayer1.." + s;
                default -> "以该名字注入测试玩家（可逗号分隔多个）";
            }));
        };
    }

    /** groups 的名单（逗号分隔） */
    public static SuggestionProvider<CommandSourceStack> playerListProvider() {
        return (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            List<String> candidates = knownPlayers(server);
            TokenSuggestions token = token(builder.getRemaining(), ',', candidates);
            return offerToken(builder, token, s -> appendUuid(server, s,
                    "把 " + s + " 加入本次 dry-run 名单"));
        };
    }

    /**
     * perm set 的单个 greedyString：命令路径 + 等级。
     * <p>
     * 语法已改为“一个 greedyString 自己拆最后一段”，因为 Brigadier 的 greedyString 必须位于末尾，
     * 旧写法 {@code greedyString("command") + word("level")} 的 level 分支永远解析不到。
     * 这里据此补全：已输入完整命令路径 + 空格 → 补等级；否则补命令路径。
     */
    public static SuggestionProvider<CommandSourceStack> permInputProvider() {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining();
            List<String> paths = commandPaths();
            int lastSpace = CompletionSupport.lastSeparator(remaining, ' ');
            String head = lastSpace < 0 ? "" : remaining.substring(0, lastSpace).trim();
            boolean trailingSpace = !remaining.isEmpty()
                    && Character.isWhitespace(remaining.charAt(remaining.length() - 1));

            if (paths.contains(head)) {
                // 已经写完命令路径：补等级（只替换最后一段）
                TokenSuggestions token = token(remaining, ' ', permissionLevels());
                return offerToken(builder, token, s -> "把 /samsara " + head + " 设为 " + s + " 级");
            }
            if (trailingSpace && head.isEmpty()) {
                return CompletionSupport.offerWholeRemaining(builder, paths, s -> "/samsara " + s);
            }
            // 还没写完路径：路径里本身可能含空格，替换整段 remaining
            return CompletionSupport.offerWholeRemaining(builder, filter(remaining.trim(), paths), s -> "/samsara " + s);
        };
    }

    /** 区块坐标 X：默认当前玩家所在区块 */
    public static SuggestionProvider<CommandSourceStack> chunkXProvider() {
        return (ctx, builder) -> chunkProvider(ctx, builder, true);
    }

    /** 区块坐标 Z：默认当前玩家所在区块 */
    public static SuggestionProvider<CommandSourceStack> chunkZProvider() {
        return (ctx, builder) -> chunkProvider(ctx, builder, false);
    }

    private static CompletableFuture<Suggestions> chunkProvider(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder, boolean x) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        int value = x ? player.blockPosition().getX() >> 4 : player.blockPosition().getZ() >> 4;
        return CompletionSupport.offer(builder, List.of(String.valueOf(value)),
                s -> x ? "你当前所在区块 X = " + s : "你当前所在区块 Z = " + s);
    }

    /** 进度跳转候选（相对时间 / 百分比） */
    public static SuggestionProvider<CommandSourceStack> seekProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder,
                List.of("-30s", "-10s", "+10s", "+30s", "25%", "50%", "75%"),
                s -> "跳转到 " + s);
    }

    /** 自瞄开关候选 */
    public static SuggestionProvider<CommandSourceStack> aimProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, List.of("on", "off"),
                s -> "自动瞄准" + (s.equals("on") ? "开启" : "关闭"));
    }

    /** 拖动模式开关候选 */
    public static SuggestionProvider<CommandSourceStack> scrubProvider() {
        return (ctx, builder) -> CompletionSupport.offer(builder, List.of("on", "off"),
                s -> "拖动模式" + (s.equals("on") ? "开启（A/D/空格/Shift）" : "关闭"));
    }

    /** 固定候选（true/false、start/end、add/remove 等小枚举） */
    public static SuggestionProvider<CommandSourceStack> choices(String... values) {
        return choices(null, values);
    }

    /** 固定候选 + 统一 hover 说明。 */
    public static SuggestionProvider<CommandSourceStack> choicesWithHint(String hint, String... values) {
        List<String> list = List.of(values);
        return (ctx, builder) -> CompletionSupport.offer(builder, list, s -> hint);
    }

    public static SuggestionProvider<CommandSourceStack> choices(Function<String, String> tooltip, String... values) {
        List<String> list = List.of(values);
        return (ctx, builder) -> CompletionSupport.offer(builder, list, tooltip);
    }

    // ---- 内部：构造 Suggestions（委托给纯工具层） ----

    /** 普通参数：用当前 remaining 做前缀过滤，替换范围就是 Brigadier 给的范围。 */
    public static CompletableFuture<Suggestions> offer(SuggestionsBuilder builder,
                                                       Collection<String> candidates) {
        return CompletionSupport.offer(builder, candidates, null);
    }

    public static CompletableFuture<Suggestions> offer(SuggestionsBuilder builder,
                                                       Collection<String> candidates,
                                                       Function<String, String> tooltip) {
        return CompletionSupport.offer(builder, candidates, tooltip);
    }

    /** 贪心参数：替换范围从 {@link TokenSuggestions#offset()} 到末尾。 */
    public static CompletableFuture<Suggestions> offerToken(SuggestionsBuilder builder,
                                                            TokenSuggestions token,
                                                            Function<String, String> tooltip) {
        int offset = Math.max(0, Math.min(token.offset(), builder.getRemaining().length()));
        return CompletionSupport.offerRange(builder, builder.getStart() + offset,
                token.candidates(), tooltip);
    }

    /** 整段 remaining 作为替换范围（命令路径这种本身含空格的候选）。 */
    public static CompletableFuture<Suggestions> offerWholeRemaining(SuggestionsBuilder builder,
                                                                     Collection<String> candidates,
                                                                     Function<String, String> tooltip) {
        return CompletionSupport.offerWholeRemaining(builder, candidates, tooltip);
    }

    // ---- 内部：工具 ----

    /** 回看选项/值的 hover 说明（Tab 与聊天栏候选共用）。 */
    public static String viewTooltip(String candidate) {
        return SuggestionCatalog.viewTooltip(candidate, cycles());
    }
}
