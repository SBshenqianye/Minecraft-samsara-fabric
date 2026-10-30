package com.shenqianye.samsara.command.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把候选排成聊天栏里的多列网格。
 *
 * <p>原版 Tab 补全是一行一个候选。聊天栏里没有这个限制，因此把短候选横向排布，
 * 一行放多个，同时仍然整块可点击（{@link ChatUi#suggest}）：
 * <ul>
 *   <li>每个候选是一个独立按钮，点击只填入聊天框、不执行；</li>
 *   <li>按近似像素宽度贪心换行，<b>不拆开</b>单个按钮；</li>
 *   <li>服务端拿不到客户端字体，宽度按半角 6 / 全角 9 估算，只影响排版不影响功能。</li>
 * </ul>
 *
 * <p>本类只负责“怎么排”，候选从哪来由 {@code IncompleteCommandHandler} 决定。
 */
public final class CompletionGrid {
    /** 一行最多占用的估算宽度（原版聊天框约 320px，留出边距）。 */
    public static final int DEFAULT_MAX_WIDTH = CompletionSupport.CHAT_WIDTH;
    /** 单元格之间固定两个空格，避免按钮粘连。 */
    public static final String SEPARATOR = "  ";
    /** 单个候选标签的最大显示宽度，超出用省略号；点击仍填入完整命令。 */
    public static final int MAX_LABEL_WIDTH = 180;

    private CompletionGrid() {
    }

    /**
     * 一个候选格。
     *
     * @param label   显示文字（不含方括号，由 {@link ChatUi#suggest} 统一加）
     * @param command 点击后填入聊天框的完整命令（可省略前导 {@code /}）
     * @param tooltip 悬停说明
     */
    public record Cell(String label, String command, String tooltip) {
    }

    public static List<MutableComponent> render(List<Cell> cells) {
        return render(cells, DEFAULT_MAX_WIDTH);
    }

    /**
     * 贪心排成若干行。空行不会产生；所有候选都放得下时返回单行。
     */
    public static List<MutableComponent> render(List<Cell> cells, int maxWidth) {
        List<MutableComponent> lines = new ArrayList<>();
        if (cells == null || cells.isEmpty()) {
            return lines;
        }
        int width = Math.max(48, maxWidth);
        MutableComponent line = Component.empty();
        int lineWidth = 0;

        for (Cell cell : cells) {
            if (cell == null || cell.label() == null || cell.label().isBlank()) {
                continue;
            }
            String label = CompletionSupport.truncate(cell.label(), MAX_LABEL_WIDTH);
            MutableComponent button = ChatUi.suggest(label, cell.command(), cell.tooltip());
            int buttonWidth = CompletionSupport.displayWidth(button.getString());
            int separatorWidth = line.getString().isEmpty()
                    ? 0
                    : CompletionSupport.displayWidth(SEPARATOR);

            if (!line.getString().isEmpty() && lineWidth + separatorWidth + buttonWidth > width) {
                lines.add(line);
                line = Component.empty();
                lineWidth = 0;
                separatorWidth = 0;
            }
            if (separatorWidth > 0) {
                line.append(SEPARATOR);
                lineWidth += separatorWidth;
            }
            line.append(button);
            lineWidth += buttonWidth;
        }
        if (!line.getString().isEmpty()) {
            lines.add(line);
        }
        return lines;
    }
}
