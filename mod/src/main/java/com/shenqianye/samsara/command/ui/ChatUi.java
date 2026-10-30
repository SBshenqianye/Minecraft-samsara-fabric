package com.shenqianye.samsara.command.ui;

import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * 聊天栏可点击组件（按钮）统一构造器。
 *
 * <h3>为什么集中在这里</h3>
 * 点击按钮 = 一段带 {@link ClickEvent} 的 {@link Component}。分散在各命令类里手搓，
 * 很容易出现"有的带 {@code /}、有的不带"、"hover 文案不一致"、"颜色各写一套"，
 * 更危险的是把破坏性命令误做成点击即执行。本类把两类点击固定成两个入口：
 * <ul>
 *   <li>{@link #run(String, String, String)} —— {@link ClickEvent.RunCommand}：点击立即执行；</li>
 *   <li>{@link #suggest(String, String, String)} —— {@link ClickEvent.SuggestCommand}：点击只填入聊天框，不执行。</li>
 * </ul>
 *
 * <h3>使用口径（与《设计规范》〈可点击指令与 Tab 补全〉一致）</h3>
 * <ul>
 *   <li>只读、幂等、用户意图明确 → {@code run}（如 version / status / where / 回看控制）；</li>
 *   <li>破坏性、管理员、需要参数、容易误触 → {@code suggest}（如 season start / debug * / perm set）。</li>
 * </ul>
 * 点击命令以玩家身份执行，仍然完整经过 Brigadier 的 {@code requires(...)} 与
 * {@link PermissionChecker}，本类<b>不</b>绕过任何权限检查。
 *
 * <h3>样式</h3>
 * 带括号按钮统一渲染为 {@code [标签]}：run 用青色（{@link ChatFormatting#AQUA}），
 * suggest 用黄色（{@link ChatFormatting#YELLOW}）；hover 为纯文本说明。
 * 需要整行可点击（例如回看时段列表）时用 {@link #runText}/{@link #suggestText}，不自带方括号。
 * <p>
 * 注意：{@code ChatFormat.wrap} 会在空格处断行，可能把坐标或长命令拆开；
 * 含按钮的长行由调用方自己按行拆开，不要让按钮文本参与自动换行。
 */
public final class ChatUi {
    /** 点击即执行按钮颜色 */
    public static final ChatFormatting RUN_COLOR = ChatFormatting.AQUA;
    /** 点击只填入按钮颜色 */
    public static final ChatFormatting SUGGEST_COLOR = ChatFormatting.YELLOW;
    /** 权限不足时按钮的替代颜色（保留占位，不显示可点击事件） */
    public static final ChatFormatting DISABLED_COLOR = ChatFormatting.DARK_GRAY;

    private ChatUi() {
    }

    // ---- 按钮 ----

    /** 点击立即执行（带方括号）。command 可省略前导 {@code /}。 */
    public static MutableComponent run(String label, String command, String hover) {
        return button(label, command, hover, true, true);
    }

    /** 点击只填入聊天框（带方括号）。command 可省略前导 {@code /}。 */
    public static MutableComponent suggest(String label, String command, String hover) {
        return button(label, command, hover, false, true);
    }

    /** 点击立即执行，但不额外加方括号（用于整行可点击列表）。 */
    public static MutableComponent runText(String text, String command, String hover) {
        return button(text, command, hover, true, false);
    }

    /** 点击只填入，但不额外加方括号（用于整行可点击列表）。 */
    public static MutableComponent suggestText(String text, String command, String hover) {
        return button(text, command, hover, false, false);
    }

    // ---- 按权限决定是否显示按钮 ----

    /**
     * 有权限时返回可点击按钮，无权限时返回灰色不可点击的同名文本。
     * 适合"按钮只对能执行的人展示"的场景；点击执行时服务端仍会再校验一次。
     */
    public static MutableComponent runIf(boolean allowed, String label, String command, String hover) {
        return allowed ? run(label, command, hover) : disabled(label);
    }

    public static MutableComponent suggestIf(boolean allowed, String label, String command, String hover) {
        return allowed ? suggest(label, command, hover) : disabled(label);
    }

    /** 按 {@code /samsara <permissionPath>} 的权限等级决定是否显示 run 按钮。 */
    public static MutableComponent runIfPermitted(CommandSourceStack src, String permissionPath,
                                                  String label, String command, String hover) {
        return runIf(src != null && PermissionChecker.check(src, permissionPath), label, command, hover);
    }

    /** 按 {@code /samsara <permissionPath>} 的权限等级决定是否显示 suggest 按钮。 */
    public static MutableComponent suggestIfPermitted(CommandSourceStack src, String permissionPath,
                                                      String label, String command, String hover) {
        return suggestIf(src != null && PermissionChecker.check(src, permissionPath), label, command, hover);
    }

    // ---- 文本/整行 ----

    /** 普通文本，指定颜色。 */
    public static MutableComponent text(String text, ChatFormatting color) {
        return Component.literal(text == null ? "" : text).withStyle(color == null ? ChatFormatting.WHITE : color);
    }

    /** 普通文本，白色。 */
    public static MutableComponent text(String text) {
        return text(text, ChatFormatting.WHITE);
    }

    /**
     * 把若干组件拼成一行：相邻部分之间补一个空格（已有空白则不重复补）。
     * 返回 {@link MutableComponent}，可直接继续 append。
     */
    public static MutableComponent line(Component... parts) {
        MutableComponent out = Component.empty();
        if (parts == null) {
            return out;
        }
        String rendered = "";
        for (Component part : parts) {
            if (part == null) {
                continue;
            }
            String next = part.getString();
            if (!rendered.isEmpty() && !next.isEmpty()
                    && !Character.isWhitespace(rendered.charAt(rendered.length() - 1))
                    && !Character.isWhitespace(next.charAt(0))) {
                out.append(" ");
            }
            out.append(part);
            rendered = next.isEmpty() ? rendered : rendered + next;
        }
        return out;
    }

    // ---- 发送 ----

    /** 按顺序发送若干行到命令源（控制台/玩家都可）。 */
    public static void send(CommandSourceStack src, Component... lines) {
        if (src == null || lines == null) {
            return;
        }
        for (Component line : lines) {
            if (line != null) {
                src.sendSuccess(() -> line, false);
            }
        }
    }

    /** 按顺序发送若干行给玩家。 */
    public static void send(ServerPlayer player, Component... lines) {
        if (player == null || lines == null) {
            return;
        }
        for (Component line : lines) {
            if (line != null) {
                player.sendSystemMessage(line);
            }
        }
    }

    // ---- 内部 ----

    private static MutableComponent button(String label, String command, String hover,
                                           boolean execute, boolean brackets) {
        String text = label == null ? "" : label;
        if (brackets) {
            text = wrapBrackets(text);
        }
        String normalized = normalizeCommand(command);
        // SuggestCommand 的用途是“填进聊天框”。补全按钮常需要带一个尾随空格，
        // 让玩家能继续输入下一个参数；normalizeCommand 会 trim，这里单独保留。
        if (command != null && !command.isBlank()
                && Character.isWhitespace(command.charAt(command.length() - 1))
                && !normalized.endsWith(" ")) {
            normalized = normalized + " ";
        }
        String tooltip = hover == null || hover.isBlank() ? normalized : hover;
        ClickEvent click = execute
                ? new ClickEvent.RunCommand(normalized)
                : new ClickEvent.SuggestCommand(normalized);
        Style style = Style.EMPTY
                .withColor(execute ? RUN_COLOR : SUGGEST_COLOR)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip)));
        return Component.literal(text).setStyle(style);
    }

    /** 已带方括号的标签不再重复包裹，避免出现 {@code [[停止]]}。 */
    private static String wrapBrackets(String label) {
        String trimmed = label.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed;
        }
        return "[" + trimmed + "]";
    }

    /** 命令统一以 {@code /} 开头；空命令原样返回（调用方应保证非空）。 */
    public static String normalizeCommand(String command) {
        String value = command == null ? "" : command.trim();
        if (value.isEmpty()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static MutableComponent disabled(String label) {
        String text = label == null ? "" : label;
        return Component.literal(wrapBrackets(text)).withStyle(DISABLED_COLOR);
    }
}
