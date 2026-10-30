package com.shenqianye.samsara.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.CompletionGrid;
import com.shenqianye.samsara.command.ui.CompletionSupport;
import com.shenqianye.samsara.config.CommandConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

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
 * <h3>与 Tab 补全同源</h3>
 * 候选来自 {@code CommandDispatcher.getCompletionSuggestions(parse)}，即与 Tab 补全
 * 使用同一套 {@code SuggestionProvider}；聊天栏只是换了一种展示方式。
 */
public final class IncompleteCommandHandler {
    /** 近似匹配最多展示的候选数 */
    private static final int FUZZY_LIMIT = 8;

    /** 最近一次由本处理器接管的指令（诊断用；只保留一行摘要）。 */
    private static volatile String lastHandled = "";

    private IncompleteCommandHandler() {
    }

    /** 最近一次接管的摘要；没有接管过时为空串。 */
    public static String lastHandled() {
        return lastHandled;
    }

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
        if (command.isEmpty() || !CommandAliases.isSamsaraRoot(firstToken(command))) {
            return false;
        }
        if (!isIncomplete(parse)) {
            return false;
        }

        CommandNode<CommandSourceStack> node = deepestNode(parse);
        Suggestions suggestions;
        try {
            suggestions = source.getServer().getCommands().getDispatcher()
                    .getCompletionSuggestions(parse).join();
        } catch (RuntimeException e) {
            return false;
        }

        boolean fuzzy = false;
        if (suggestions.getList().isEmpty() && config.isCompletionFuzzyEnabled()) {
            suggestions = fuzzySuggestions(command, parse, node);
            fuzzy = !suggestions.getList().isEmpty();
        }
        if (suggestions.getList().isEmpty()) {
            return false;
        }

        List<CompletionGrid.Cell> cells = cells(command, suggestions, node);
        if (cells.isEmpty()) {
            return false;
        }
        int maxItems = Math.max(1, config.completionMaxItems());
        boolean truncated = cells.size() > maxItems;
        List<CompletionGrid.Cell> shown = truncated ? cells.subList(0, maxItems) : cells;

        String header = (fuzzy ? "未知指令：" : "指令未完成：") + command
                + (fuzzy ? " · 你是不是想输入（点击填入）" : " · 点击选项填入聊天框（" + cells.size() + " 项）");
        ChatUi.send(player, ChatUi.text(header, ChatFormatting.GRAY));
        for (MutableComponent line : CompletionGrid.render(shown)) {
            ChatUi.send(player, line);
        }
        if (truncated) {
            ChatUi.send(player, ChatUi.text("还有 " + (cells.size() - maxItems)
                    + " 项，继续输入或按 Tab 缩小范围", ChatFormatting.DARK_GRAY));
        }
        lastHandled = command + " · " + cells.size() + " 项";
        return true;
    }

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

    static CommandNode<CommandSourceStack> deepestNode(ParseResults<CommandSourceStack> parse) {
        List<ParsedCommandNode<CommandSourceStack>> nodes = parse.getContext().getNodes();
        return nodes.isEmpty() ? parse.getContext().getRootNode() : nodes.get(nodes.size() - 1).getNode();
    }

    /**
     * 把 Brigadier 候选转成聊天栏格：命令 = 原始前缀 + 候选文本。
     * 若候选是“还有子命令、自己不能执行”的字面量节点，额外补一个尾随空格，
     * 方便玩家继续输入下一段。
     */
    static List<CompletionGrid.Cell> cells(String command, Suggestions suggestions,
                                           CommandNode<CommandSourceStack> node) {
        StringRange range = suggestions.getRange();
        int start = Math.max(0, Math.min(range.getStart(), command.length()));
        String prefix = command.substring(0, start);
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
            if (child != null && child.getCommand() == null && !child.getChildren().isEmpty()) {
                filled = filled + " ";
            }
            String tooltip = suggestion.getTooltip() == null
                    ? "点击填入：" + ChatUi.normalizeCommand(filled)
                    : suggestion.getTooltip().getString();
            cells.add(new CompletionGrid.Cell(text, filled, tooltip));
        }
        return cells;
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
