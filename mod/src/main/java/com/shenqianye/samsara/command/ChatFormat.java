package com.shenqianye.samsara.command;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令返回信息格式化工具（统一消息格式）。
 *
 * <h3>格式规范</h3>
 * <ul>
 *   <li>首行以 {@code [周目管理] ▸ } 开头，便于与上一条消息视觉区分</li>
 *   <li>后续行以 {@code   ▸ } 缩进对齐，形成清晰的多行层次</li>
 *   <li>自动换行：CJK 字符按 2 宽度、ASCII 按 1 宽度计算，默认最大 34 宽度单位</li>
 * </ul>
 *
 * <h3>格式示例（MC 聊天框中的显示效果）</h3>
 * <pre>
 * [周目管理] ▸ 圈地旗帜（3 面）·保护区块 12 个：
 *   ▸ [0,0] 朝北 图案2个
 *   ▸ [1,0] 朝南 图案1个
 *   ▸ 数据文件: xxx
 * </pre>
 *
 * <h3>使用此格式的命令</h3>
 * <table border="1" cellpadding="4">
 *   <tr><th>命令路径</th><th>格式类型</th></tr>
 *   <tr><td>/samsara season status|info|create|start|end|skip|respawn-groups</td><td>常规</td></tr>
 *   <tr><td>/samsara claim give|list|info|rebuild|scan|preview|remove|export|boundary|particle</td><td>常规</td></tr>
 *   <tr><td>/samsara version</td><td>常规</td></tr>
 *   <tr><td>/samsara debug *（所有调试命令）</td><td>调试</td></tr>
 * </table>
 *
 * <h3>换行宽度</h3>
 * 默认 34 宽度单位（CJK=2, ASCII=1）。可通过 {@link #setPlayerWrapWidth(UUID, int)}
 * 为每个玩家单独设置（通过 /samsara settings wrapwidth 命令）。
 *
 * @see #format(String)
 * @see #formatDebug(String)
 * @see #formatComponent(String)
 */
public class ChatFormat {

    // ---- 常量 ----

    /** 常规消息头部标签 */
    private static final String HEADER = "[周目管理]";
    /** 调试消息头部标签 */
    private static final String HEADER_DEBUG = "[周目管理·调试]";
    /** 行首符号（用于视觉分隔，区分连续消息） */
    private static final String BULLET = "\u25B8"; // ▸ BLACK RIGHT-POINTING SMALL TRIANGLE
    /** 首行前缀：头部 + 空格 + 符号 + 空格 */
    private static final String PREFIX_FIRST = HEADER + " " + BULLET + " ";
    /** 续行前缀：缩进 + 符号 + 空格 */
    private static final String PREFIX_CONTINUE = "  " + BULLET + " ";
    /** 调试首行前缀 */
    private static final String PREFIX_DEBUG_FIRST = HEADER_DEBUG + " " + BULLET + " ";

    /** 默认最大显示宽度（宽度单位：CJK=2, ASCII=1） */
    public static final int DEFAULT_MAX_WIDTH = 42;

    /** 首行前缀显示宽度（缓存，避免重复计算） */
    private static final int PREFIX_FIRST_WIDTH = displayWidth(PREFIX_FIRST);
    /** 续行前缀显示宽度（缓存，避免重复计算） */
    private static final int PREFIX_CONTINUE_WIDTH = displayWidth(PREFIX_CONTINUE);

    // ---- 玩家级换行宽度设置 ----

    /**
     * 各玩家的自定义换行宽度（UUID → 宽度单位）。
     * <p>
     * 未设置的玩家使用 {@link #DEFAULT_MAX_WIDTH}。
     * 值为 0 或负数时禁用自动换行。
     */
    private static final ConcurrentHashMap<UUID, Integer> playerWrapWidth = new ConcurrentHashMap<>();

    /**
     * 设置玩家的换行宽度。
     *
     * @param playerUuid 玩家 UUID（null 时设置全局默认）
     * @param width      宽度单位（0 或负数 = 禁用自动换行；34 = 默认）
     */
    public static void setPlayerWrapWidth(UUID playerUuid, int width) {
        if (playerUuid == null) {
            // null UUID = 全局默认（暂不支持，仅记录日志）
            com.shenqianye.samsara.Samsara.LOGGER.warn(
                    "[ChatFormat] 全局默认宽度设置暂不支持，请指定玩家 UUID");
            return;
        }
        if (width <= 0) {
            playerWrapWidth.remove(playerUuid);
        } else {
            playerWrapWidth.put(playerUuid, width);
        }
    }

    /**
     * 获取玩家的换行宽度。
     *
     * @param playerUuid 玩家 UUID
     * @return 宽度单位，未设置则返回 {@link #DEFAULT_MAX_WIDTH}
     */
    public static int getPlayerWrapWidth(UUID playerUuid) {
        if (playerUuid == null) return DEFAULT_MAX_WIDTH;
        return playerWrapWidth.getOrDefault(playerUuid, DEFAULT_MAX_WIDTH);
    }

    // ---- 显示宽度计算 ----

    /**
     * 计算字符串的显示宽度。
     * <p>
     * CJK 字符（全角）计为 2，其它字符计为 1。
     *
     * @param s 输入字符串
     * @return 显示宽度（宽度单位）
     */
    public static int displayWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
        }
        return w;
    }

    /**
     * 计算单个字符的显示宽度。
     *
     * @param c 字符
     * @return 宽度（CJK 全角=2, 其它=1）
     */
    public static int charWidth(char c) {
        return isFullWidth(c) ? 2 : 1;
    }

    /**
     * 判断字符是否为全角（CJK）字符。
     * <p>
     * 覆盖范围：CJK 统一汉字、CJK 兼容汉字、平假名/片假名、
     * 韩文字母/音节、全角 ASCII、中文标点等。
     *
     * @param c 字符
     * @return 如果是全角字符返回 true
     */
    private static boolean isFullWidth(char c) {
        return (c >= 0x1100 && c <= 0x115F)   // 韩文字母
                || (c >= 0x2E80 && c <= 0x303E) // CJK 部首、康熙部首等
                || (c >= 0x3040 && c <= 0x33BF) // 平假名、片假名、CJK 兼容
                || (c >= 0x3400 && c <= 0x4DBF) // CJK 统一汉字扩展 A
                || (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一汉字（基本区）
                || (c >= 0xA000 && c <= 0xA4CF) // 彝文
                || (c >= 0xAC00 && c <= 0xD7AF) // 韩文音节
                || (c >= 0xF900 && c <= 0xFAFF) // CJK 兼容汉字
                || (c >= 0xFE30 && c <= 0xFE4F) // CJK 兼容形式
                || (c >= 0xFF01 && c <= 0xFF60) // 全角 ASCII、半角片假名
                || (c >= 0xFFE0 && c <= 0xFFE6) // 全角货币符号
                || (c >= 0x20000 && c <= 0x2FA1F); // CJK 扩展 B~F
    }

    // ---- 自动换行 ----

    /**
     * 将文本按显示宽度自动换行。
     * <p>
     * 在 {@code maxWidth} 宽度单位处断行，优先在空格处断开。
     * 已有的 {@code \n} 换行会被保留，每段独立换行。
     *
     * @param text      输入文本（可包含 {@code \n}）
     * @param maxWidth  最大显示宽度（宽度单位）
     * @return 换行后的文本
     */
    public static String wrap(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            result.append(wrapSingleLine(lines[i], maxWidth));
        }
        return result.toString();
    }

    /**
     * 对单行文本进行自动换行（不含 {@code \n}）。
     * <p>
     * 策略：优先保持完整句子（以句号/叹号/问号/冒号结尾的片段）不拆分。
     * 如果整行不超过 {@code maxWidth}，直接返回不换行。
     * 如果超过，先按句子边界拆分，每个句子尽量保持完整；
     * 只有单个句子超出 {@code maxWidth} 时才按空格断行。
     *
     * @param line      单行文本（不含 {@code \n}）
     * @param maxWidth  最大显示宽度
     * @return 换行后的文本
     */
    private static String wrapSingleLine(String line, int maxWidth) {
        if (line.isEmpty() || displayWidth(line) <= maxWidth) return line;

        // 按句子边界拆分（保持句号/叹号/问号/冒号在片段末尾）
        List<String> sentences = splitSentences(line);

        // 如果只有一个句子且超过宽度，按空格断行
        if (sentences.size() == 1) {
            return wrapByWords(line, maxWidth);
        }

        // 多个句子：逐句尝试拼接到当前行
        StringBuilder result = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (String sentence : sentences) {
            int sentenceWidth = displayWidth(sentence);

            if (currentLine.length() == 0) {
                // 空行，直接放入
                currentLine.append(sentence);
                currentWidth = sentenceWidth;
            } else if (currentWidth + sentenceWidth <= maxWidth) {
                // 当前行放得下，拼接
                currentLine.append(sentence);
                currentWidth += sentenceWidth;
            } else {
                // 当前行放不下，换行
                result.append(currentLine).append("\n");
                currentLine = new StringBuilder(sentence);
                currentWidth = sentenceWidth;
            }
        }

        if (currentLine.length() > 0) {
            result.append(currentLine);
        }
        return result.toString();
    }

    /**
     * 按句子边界拆分文本。
     * 句子终止符：中文句号/叹号/问号/冒号（全角）
     * 半角冒号 ":" 不作为断句符（避免时间格式 HH:mm:ss 被拆断）。
     * 终止符保留在片段末尾。
     */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '：') {
                sentences.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /**
     * 按空格断行（兜底方案，单句超宽时使用）。
     * 智能处理：日期时间模式（yyyy-MM-dd HH:mm:ss）作为整体不拆分。
     */
    private static String wrapByWords(String line, int maxWidth) {
        // 先把时间戳中的空格替换为临时占位符，避免被拆断
        String processed = line.replaceAll("(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})", "$1\u00A0$2");
        String[] rawWords = processed.split(" ");
        List<String> words = new ArrayList<>();
        for (String w : rawWords) {
            if (!w.isEmpty()) words.add(w.replace('\u00A0', ' '));
        }
        if (words.isEmpty()) return line;

        StringBuilder result = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            int wordWidth = displayWidth(word);

            if (currentLine.length() > 0) {
                if (currentWidth + 1 + wordWidth > maxWidth) {
                    result.append(currentLine).append("\n");
                    currentLine = new StringBuilder();
                    currentWidth = 0;
                } else {
                    currentLine.append(" ");
                    currentWidth += 1;
                }
            }

            currentLine.append(word);
            currentWidth += wordWidth;
        }

        result.append(currentLine);
        return result.toString();
    }

    // ---- 格式化方法（常规） ----

    /**
     * 格式化常规消息为 {@link Component}（带前缀和自动换行）。
     * <p>
     * 使用默认换行宽度（34 宽度单位）。
     *
     * @param message 原始消息文本
     * @return 格式化后的 Component，可直接用于 sendSuccess
     */
    public static Component formatComponent(String message) {
        return Component.literal(format(message));
    }

    /**
     * 格式化常规消息为 {@link Component}（使用指定玩家的换行宽度）。
     *
     * @param message    原始消息文本
     * @param playerUuid 玩家 UUID（用于读取该玩家的换行宽度设置）
     * @return 格式化后的 Component
     */
    public static Component formatComponent(String message, UUID playerUuid) {
        int width = getPlayerWrapWidth(playerUuid);
        return Component.literal(formatInternal(message, PREFIX_FIRST, width));
    }

    /**
     * 格式化常规消息字符串（带前缀和自动换行）。
     * <p>
     * 格式示例：
     * <pre>
     * [周目管理] ▸ 圈地旗帜（3 面）·保护区块 12 个：
     *   ▸ [0,0] 朝北 图案2个
     *   ▸ [1,0] 朝南 图案1个
     * </pre>
     *
     * @param message 原始消息文本
     * @return 格式化后的字符串
     */
    public static String format(String message) {
        return formatInternal(message, PREFIX_FIRST, DEFAULT_MAX_WIDTH);
    }

    // ---- 格式化方法（调试） ----

    /**
     * 格式化调试消息为 {@link Component}（带前缀和自动换行）。
     *
     * @param message 原始消息文本
     * @return 格式化后的 Component
     */
    public static Component formatDebugComponent(String message) {
        return Component.literal(formatDebug(message));
    }

    /**
     * 格式化调试消息字符串（带前缀和自动换行）。
     * <p>
     * 格式示例：
     * <pre>
     * [周目管理·调试] ▸ 周目总数: 3 · 当前: #1「初始」 ACTIVE
     *   ▸ 配置目录: /path/to/config
     * </pre>
     *
     * @param message 原始消息文本
     * @return 格式化后的字符串
     */
    public static String formatDebug(String message) {
        return formatInternal(message, PREFIX_DEBUG_FIRST, DEFAULT_MAX_WIDTH);
    }

    // ---- 内部实现 ----

    /**
     * 内部格式化实现。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>按 {@code \n} 分段，保留原始段落结构</li>
     *   <li>第一段使用首行前缀，后续段使用续行前缀</li>
     *   <li>每段独立按 maxWidth 自动换行</li>
     *   <li>换行后的物理行统一使用续行前缀</li>
     * </ol>
     *
     * @param message     原始消息
     * @param firstPrefix 首行前缀（如 "[周目管理] ▸ "）
     * @param maxWidth    最大显示宽度
     * @return 格式化后的字符串
     */
    private static String formatInternal(String message, String firstPrefix, int maxWidth) {
        // 内容宽度直接用 maxWidth（前缀是视觉装饰，不计入内容宽度）
        int contentWidth = maxWidth;
        // 保证最小内容宽度
        if (contentWidth < 10) contentWidth = 10;

        // 按 \n 分段
        String[] segments = message.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        boolean firstPhysical = true;

        for (String segment : segments) {
            // 每段独立换行
            String[] wrapped = wrapSingleLine(segment, contentWidth).split("\n", -1);

            for (String line : wrapped) {
                if (!firstPhysical) sb.append("\n");
                sb.append(firstPhysical ? firstPrefix : PREFIX_CONTINUE);
                sb.append(line);
                firstPhysical = false;
            }
        }

        return sb.toString();
    }

    /**
     * 获取续行前缀（用于外部工具类或测试）。
     *
     * @return 续行前缀字符串
     */
    public static String getContinuePrefix() {
        return PREFIX_CONTINUE;
    }

    /**
     * 获取首行前缀（用于外部工具类或测试）。
     *
     * @return 首行前缀字符串
     */
    public static String getFirstPrefix() {
        return PREFIX_FIRST;
    }

    /**
     * 获取调试首行前缀（用于外部工具类或测试）。
     *
     * @return 调试首行前缀字符串
     */
    public static String getDebugFirstPrefix() {
        return PREFIX_DEBUG_FIRST;
    }
}
