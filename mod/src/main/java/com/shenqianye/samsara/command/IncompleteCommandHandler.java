package com.shenqianye.samsara.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.CompletionAction;
import com.shenqianye.samsara.command.ui.CompletionGrid;
import com.shenqianye.samsara.command.ui.CompletionPaging;
import com.shenqianye.samsara.command.ui.CompletionSupport;
import com.shenqianye.samsara.config.CommandConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未完成 / 拼错指令时，把候选直接发到聊天栏（可点击填入）。
 *
 * <h3>为什么在服务端做</h3>
 * 原版在回车后才发现「指令不完整」，并把错误作为一行文字发给客户端；客户端此时已经
 * 关闭聊天输入。这里在服务端拦截这一瞬间：解析失败时先算出 Brigadier 的补全候选，
 * 再用 {@link CompletionGrid} 排成多列、每项带 {@code SuggestCommand} 点击事件发回。
 * 客户端不需要安装模组。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>只接管 samsara 自己的根节点（{@code /samsara} 与已注册短别名，见
 *       {@link CommandAliases#isSamsaraRoot(String)}），不改其他模组的报错；</li>
 *   <li>只处理“解析不完整 / 没有可执行节点”，命令执行期抛出的异常仍走原版处理；</li>
 *   <li>控制台不发送可点击组件（没有聊天框），保留原版错误；</li>
 *   <li>候选为空时返回 false，让原版错误照常显示，避免静默吞掉错误。</li>
 * </ul>
 *
 * <h3>补全交互 2.0（F-115~F-117）</h3>
 * <ul>
 *   <li><b>有执行器的节点</b>：执行结果额外给一个 {@code [展开子命令]}，点击填入
 *       {@code <节点> } 并由客户端自动弹候选；</li>
 *   <li><b>点击继续补全</b>：每个候选末尾由 {@link ChatUi#withSeparator} 统一补分隔符
 *       （普通参数空格、逗号列表参数逗号），命令类不得手写；</li>
 *   <li><b>翻页</b>：超过 {@code completion.maxItems} 时显示页码与
 *       {@code [上一页] [下一页]}，点击走 {@link CompletionAction} 的自定义动作，
 *       不注册隐藏命令。</li>
 * </ul>
 *
 * <p><b>不要用尾随空格区分“执行/展开”</b>：原版 {@code ChatScreen.normalizeChatMessage}
 * 在提交前会 trim + normalizeSpace，服务端收到的命令没有尾随空格。尾随空格只对
 * 客户端“填入后继续弹候选”有意义。
 */
public final class IncompleteCommandHandler {
    /** 近似匹配最多展示的候选数 */
    private static final int FUZZY_LIMIT = 8;
    /** 逗号列表参数名（点击后补逗号而不是空格） */
    private static final Set<String> LIST_ARGUMENTS =
            Set.of("names", "players", "groups", "fakeplayers", "members");

    /** 最近一次由本处理器接管的指令（诊断用；只保留一行摘要）。 */
    private static volatile String lastHandled = "";
    /** 最近一次翻页结果（诊断用）。 */
    private static volatile String lastPage = "";
    /** 玩家翻页节流（uuid → 上次毫秒） */
    private static final Map<UUID, Long> PAGE_THROTTLE = new ConcurrentHashMap<>();

    private IncompleteCommandHandler() {
    }

    /** 最近一次接管的摘要；没有接管过时为空串。 */
    public static String lastHandled() {
        return lastHandled;
    }

    /** 最近一次翻页摘要；没有翻过页时为空串。 */
    public static String lastPage() {
        return lastPage;
    }

    // ---- 入口 1：未完成指令 ----

    /**
     * 尝试接管一次未完成指令。
     *
     * @param parse       Brigadier 解析结果
     * @param rawCommand  玩家输入的命令（可能带前导 {@code /}）
     * @return true = 已把候选发到聊天栏，调用方应取消原版错误输出
     */
    public static boolean tryHandle(ParseResults<CommandSourceStack> parse, String rawCommand) {
        if (parse == null) {
            return false;
        }
        CommandConfig config = CommandConfig.getInstance();
        if (!config.isChatCompletionEnabled()) {
            return false;
        }
        CommandSourceStack source = parse.getContext().getSource();
        ServerPlayer player = source == null ? null : source.getPlayer();
        if (player == null) {
            return false;
        }
        String command = normalize(rawCommand);
        if (!isSamsaraCommand(command)) {
            return false;
        }
        if (!isIncomplete(parse)) {
            return false;
        }
        return sendCandidates(player, source, command, parse, 1);
    }

    // ---- 入口 2：有执行器的节点，执行后给“展开子命令” ----

    /**
     * 节点自身有执行器时，在命令执行结果后补一个 {@code [展开子命令]}。
     *
     * <p>不依赖尾随空格：原版客户端提交前会 trim，服务端无法区分
     * {@code /samsara season} 和 {@code /samsara season }。这里用独立的按钮表达
     * “我要看子命令”，点击只填入 {@code /samsara season }，由客户端自动弹候选。
     *
     * @return true = 已发送按钮
     */
    public static boolean appendExpandHint(ParseResults<CommandSourceStack> parse, String rawCommand) {
        if (parse == null) {
            return false;
        }
        CommandConfig config = CommandConfig.getInstance();
        if (!config.isChatCompletionEnabled()) {
            return false;
        }
        CommandSourceStack source = parse.getContext().getSource();
        ServerPlayer player = source == null ? null : source.getPlayer();
        if (player == null) {
            return false;
        }
        String command = normalize(rawCommand);
        if (!isSamsaraCommand(command) || isIncomplete(parse)) {
            return false;
        }
        if (!shouldOfferExpand(parse, source)) {
            return false;
        }
        String filled = command.endsWith(" ") ? command : command + " ";
        MutableComponent line = ChatUi.line(
                ChatUi.text("要继续查看子命令：", ChatFormatting.DARK_GRAY),
                ChatUi.suggest("展开子命令", filled, "填入 " + ChatUi.normalizeCommand(command)
                        + " 并打开下一层候选（不执行）"));
        ChatUi.send(player, line);
        return true;
    }

    /** 该解析结果是否值得给“展开子命令”按钮：节点有执行器、还有可用子命令。 */
    static boolean shouldOfferExpand(ParseResults<CommandSourceStack> parse,
                                     CommandSourceStack source) {
        if (parse == null) {
            return false;
        }
        CommandNode<CommandSourceStack> node = deepestNode(parse);
        if (node == null || node.getCommand() == null || node.getChildren().isEmpty()) {
            return false;
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (source == null || child.canUse(source)) {
                return true;
            }
        }
        return false;
    }

    // ---- 入口 3：翻页 ----

    /**
     * 处理 {@code samsara:completion_page} 点击。必须在服务端主线程调用。
     *
     * @return true = 已消费（无论是否真的发送，避免客户端再走原版未知动作处理）
     */
    public static boolean handlePage(ServerPlayer player, Tag payload) {
        if (player == null) {
            return false;
        }
        CommandConfig config = CommandConfig.getInstance();
        if (!config.isChatCompletionEnabled()) {
            return false;
        }
        CompletionAction.Request request = CompletionAction.parse(payload);
        if (request == null) {
            return false;
        }
        String command = normalize(request.input());
        if (!isSamsaraCommand(command)) {
            return false;
        }
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        long last = PAGE_THROTTLE.getOrDefault(id, 0L);
        if (!CompletionPaging.allowAt(last, now)) {
            return true;
        }
        PAGE_THROTTLE.put(id, now);
        CommandSourceStack source = player.createCommandSourceStack();
        ParseResults<CommandSourceStack> parse;
        try {
            parse = source.getServer().getCommands().getDispatcher().parse(command, source);
        } catch (RuntimeException e) {
            return true;
        }
        sendCandidates(player, source, command, parse, request.page());
        return true;
    }

    /** 玩家退出时清理节流表，避免 UUID 累积。 */
    public static void onDisconnect(UUID playerId) {
        if (playerId != null) {
            PAGE_THROTTLE.remove(playerId);
        }
    }

    // ---- 共用：计算候选并发送某一页 ----

    private static boolean sendCandidates(ServerPlayer player, CommandSourceStack source,
                                          String command, ParseResults<CommandSourceStack> parse,
                                          int requestedPage) {
        CommandConfig config = CommandConfig.getInstance();
        // 客户端提交前会 trim 尾随空格：`/samsara season` 到达服务端时没有尾随空格。
        // 此时 Brigadier 的补全上下文会指向 season 的父节点（samsara），返回兄弟节点而非
        // 子命令。节点自己没有执行器时补一个空格再解析，明确“我要看子命令”。
        CommandNode<CommandSourceStack> node = deepestNode(parse);
        boolean childMode = false;
        if (!parse.getReader().canRead() && node.getCommand() == null
                && !node.getChildren().isEmpty()) {
            try {
                parse = source.getServer().getCommands().getDispatcher()
                        .parse(command + " ", source);
                node = deepestNode(parse);
                childMode = true;
            } catch (RuntimeException ignored) {
                // 保持原 parse
            }
        }
        Suggestions suggestions;
        try {
            suggestions = source.getServer().getCommands().getDispatcher()
                    .getCompletionSuggestions(parse).join();
        } catch (RuntimeException e) {
            return false;
        }

        boolean fuzzy = false;
        if (suggestions.getList().isEmpty() && !childMode && config.isCompletionFuzzyEnabled()) {
            suggestions = fuzzySuggestions(command, parse, node);
            fuzzy = !suggestions.getList().isEmpty();
        }
        if (suggestions.getList().isEmpty()) {
            return false;
        }

        List<CompletionGrid.Cell> cells = cells(command, suggestions, node, childMode);
        if (cells.isEmpty()) {
            return false;
        }
        int pageSize = Math.max(1, config.completionMaxItems());
        CompletionPaging.Page<CompletionGrid.Cell> page =
                CompletionPaging.slice(cells, requestedPage, pageSize);

        String title = fuzzy ? "未知指令：" + command : "指令未完成：" + command;
        String tail = page.pageCount() > 1
                ? " · " + CompletionPaging.label(page.page(), page.pageCount(), page.total())
                : " · 点击选项填入聊天框（" + page.total() + " 项）";
        ChatUi.send(player, ChatUi.text(title + tail, ChatFormatting.GRAY));
        for (MutableComponent line : CompletionGrid.render(page.items())) {
            ChatUi.send(player, line);
        }

        if (page.total() < cells.size()) {
            ChatUi.send(player, ChatUi.text("候选过多，只展示前 " + page.total()
                    + " 项；继续输入或按 Tab 缩小范围", ChatFormatting.DARK_GRAY));
        }
        sendPageButtons(player, command, page);
        lastHandled = command + " · " + cells.size() + " 项";
        if (requestedPage > 1 || page.page() > 1) {
            lastPage = command + " · " + CompletionPaging.label(page.page(), page.pageCount(), page.total());
        }
        return true;
    }

    private static void sendPageButtons(ServerPlayer player, String command,
                                        CompletionPaging.Page<CompletionGrid.Cell> page) {
        if (page.pageCount() <= 1) {
            return;
        }
        MutableComponent prev = page.hasPrev()
                ? ChatUi.custom("上一页", CompletionAction.ID,
                        CompletionAction.payload(command, page.page() - 1, ""),
                        "显示上一页（不改变聊天输入）")
                : ChatUi.text("", ChatFormatting.DARK_GRAY);
        MutableComponent next = page.hasNext()
                ? ChatUi.custom("下一页", CompletionAction.ID,
                        CompletionAction.payload(command, page.page() + 1, ""),
                        "显示下一页（不改变聊天输入）")
                : ChatUi.text("", ChatFormatting.DARK_GRAY);
        ChatUi.send(player, ChatUi.line(prev, next));
    }

    /** 调试入口：按页展示某个片段（只用于服务端验证分页）。 */
    public static String debugPage(CommandSourceStack source, int page, String raw) {
        String command = normalize(raw);
        if (source == null || source.getPlayer() == null) {
            return "分页调试只支持玩家身份";
        }
        ServerPlayer player = source.getPlayer();
        CommandSourceStack playerSource = player.createCommandSourceStack();
        ParseResults<CommandSourceStack> parse =
                playerSource.getServer().getCommands().getDispatcher().parse(command, playerSource);
        boolean sent = sendCandidates(player, playerSource, command, parse, page);
        return sent
                ? "已按第 " + page + " 页发送候选（总 " + lastHandled + "）"
                : "没有候选可发送";
    }

    // ---- 未完成判定 / 输入归一化 ----

    /** 解析失败或没有可执行节点都算“未完成”。 */
    static boolean isIncomplete(ParseResults<?> parse) {
        // 与 Commands.getParseException 的判定一致，但不触发 Minecraft 的权限注册表初始化，
        // 便于在纯 JUnit 环境里测试。
        return parse.getReader().canRead() || parse.getContext().getCommand() == null;
    }

    /** 去掉前导 {@code /} 与首尾空白；Brigadier 的游标基于这个结果。 */
    static String normalize(String raw) {
        String value = raw == null ? "" : raw.strip();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    static String firstToken(String command) {
        String value = command == null ? "" : command.strip();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    static boolean isSamsaraCommand(String command) {
        String token = firstToken(command);
        return !token.isEmpty() && CommandAliases.isSamsaraRoot(token);
    }

    static CommandNode<CommandSourceStack> deepestNode(ParseResults<CommandSourceStack> parse) {
        List<ParsedCommandNode<CommandSourceStack>> nodes = parse.getContext().getNodes();
        return nodes.isEmpty() ? parse.getContext().getRootNode() : nodes.get(nodes.size() - 1).getNode();
    }

    /**
     * 把 Brigadier 候选转成聊天栏格：命令 = 原始前缀 + 候选文本，再统一补分隔符
     * （普通参数空格、逗号列表参数逗号）。叶子命令也补空格：玩家按回车时客户端
     * 会 trim 尾随空格，照常执行。
     */
    static List<CompletionGrid.Cell> cells(String command, Suggestions suggestions,
                                           CommandNode<CommandSourceStack> node) {
        return cells(command, suggestions, node, false);
    }

    static List<CompletionGrid.Cell> cells(String command, Suggestions suggestions,
                                           CommandNode<CommandSourceStack> node,
                                           boolean childMode) {
        StringRange range = suggestions.getRange();
        int start = Math.max(0, Math.min(range.getStart(), command.length()));
        String prefix = command.substring(0, start);
        if (childMode) {
            // 为了拿到子命令，内部解析用的是 `command + " "`；填入时也要保留这个分隔符。
            prefix = command.endsWith(" ") ? command : command + " ";
        }
        // 当前正在编辑的 token：Brigadier 的替换范围从 start 到末尾。逗号列表里
        // start 往往落在逗号之后，因此还要结合参数名判断。
        String currentToken = childMode ? "" : command.substring(start);
        List<CompletionGrid.Cell> cells = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            String text = suggestion.getText();
            if (text == null || text.isBlank() || seen.contains(text)) {
                continue;
            }
            seen.add(text);
            String filled = prefix + text;
            CommandNode<CommandSourceStack> child = node == null ? null : node.getChild(text);
            boolean list = isListArgument(child, node, currentToken, text);
            filled = ChatUi.withSeparator(filled, list);
            String tooltip = suggestion.getTooltip() == null
                    ? "点击填入：" + ChatUi.normalizeCommand(filled)
                    : suggestion.getTooltip().getString();
            cells.add(new CompletionGrid.Cell(text, filled, tooltip));
        }
        return cells;
    }

    /** 逗号列表参数：当前/下一个参数名命中，或当前 token 已经含逗号。 */
    static boolean isListArgument(CommandNode<CommandSourceStack> child,
                                  CommandNode<CommandSourceStack> node,
                                  String currentToken, String suggestionText) {
        if (isListArgumentName(child) || isListArgumentName(node)) {
            return true;
        }
        if (node != null) {
            for (CommandNode<CommandSourceStack> candidate : node.getChildren()) {
                if (isListArgumentName(candidate)) {
                    return true;
                }
            }
        }
        return (currentToken != null && currentToken.contains(","))
                || (suggestionText != null && suggestionText.endsWith(","));
    }

    private static boolean isListArgumentName(CommandNode<CommandSourceStack> node) {
        return node instanceof ArgumentCommandNode<?, ?>
                && LIST_ARGUMENTS.contains(node.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * 拼写近似兜底：Brigadier 对完全不匹配的字面量返回空列表，
     * 这里从当前节点的子节点里找编辑距离最近的几个（{@code seasn → season}）。
     */
    static Suggestions fuzzySuggestions(String command, ParseResults<CommandSourceStack> parse,
                                        CommandNode<CommandSourceStack> node) {
        int cursor = Math.max(0, Math.min(parse.getReader().getCursor(), command.length()));
        String tail = command.substring(cursor);
        int space = CompletionSupport.lastSeparator(tail, ' ');
        if (space >= 0) {
            cursor += space + 1;
        }
        String token = command.substring(cursor);
        if (token.isBlank() || !token.matches("[A-Za-z0-9_\\-]+")) {
            return Suggestions.empty().join();
        }
        int maxDistance = token.length() <= 2 ? 1 : Math.min(3, (token.length() + 1) / 3);
        List<String> children = literalChildren(node, parse.getContext().getSource());
        List<String> matches = CompletionSupport.fuzzy(token, children, maxDistance);
        if (matches.isEmpty()) {
            return Suggestions.empty().join();
        }
        if (matches.size() > FUZZY_LIMIT) {
            matches = matches.subList(0, FUZZY_LIMIT);
        }
        StringRange range = StringRange.between(cursor, command.length());
        List<Suggestion> out = new ArrayList<>();
        for (String match : matches) {
            out.add(new Suggestion(range, match));
        }
        return new Suggestions(range, out);
    }

    private static List<String> literalChildren(CommandNode<CommandSourceStack> node,
                                                CommandSourceStack source) {
        if (node == null) {
            return List.of();
        }
        Collection<CommandNode<CommandSourceStack>> children = node.getChildren();
        List<String> out = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : children) {
            if (child instanceof LiteralCommandNode && (source == null || child.canUse(source))) {
                out.add(child.getName().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
