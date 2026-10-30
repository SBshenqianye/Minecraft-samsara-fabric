package com.shenqianye.samsara.command.ui;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 补全的纯工具层：去重、前缀过滤、token 定位、候选构造、显示宽度与拼写近似。
 *
 * <p>这一层<b>不依赖具体命令</b>，也不读游戏状态，因此可以直接单测。命令侧只通过
 * {@link SuggestionProviders} 使用它；新增补全时优先在这里补“通用能力”，而不是在
 * 各命令类里手写 {@code startsWith} 或 {@code new Suggestions(...)}。
 */
public final class CompletionSupport {
    /** 聊天栏默认可用宽度（像素，原版聊天框约 320）。 */
    public static final int CHAT_WIDTH = 300;

    private CompletionSupport() {
    }

    /** 去空、去重、保留首次出现顺序。 */
    public static List<String> dedupe(Collection<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    set.add(value);
                }
            }
        }
        return List.copyOf(set);
    }

    /** 不区分大小写的前缀过滤，保留候选原顺序。 */
    public static List<String> filter(String prefix, Collection<String> candidates) {
        List<String> out = new ArrayList<>();
        if (candidates == null) {
            return out;
        }
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(candidate);
            }
        }
        return dedupe(out);
    }

    /** 最后一个分隔符的下标；{@code ' '} 表示任意空白。 */
    public static int lastSeparator(String input, char separator) {
        if (input == null || input.isEmpty()) {
            return -1;
        }
        if (separator == ' ') {
            for (int i = input.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(input.charAt(i))) {
                    return i;
                }
            }
            return -1;
        }
        return input.lastIndexOf(separator);
    }

    /**
     * 近似匹配：按编辑距离挑出拼错的候选（例如 {@code seasn → season}）。
     * 距离相同保留候选原顺序；返回值已去重。
     */
    public static List<String> fuzzy(String token, Collection<String> candidates, int maxDistance) {
        if (token == null || token.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String needle = token.toLowerCase(Locale.ROOT);
        int limit = Math.max(0, maxDistance);
        List<String> matches = new ArrayList<>();
        for (String candidate : dedupe(candidates)) {
            int distance = editDistance(needle, candidate.toLowerCase(Locale.ROOT));
            if (distance <= limit) {
                matches.add(candidate);
            }
        }
        matches.sort((a, b) -> Integer.compare(
                editDistance(needle, a.toLowerCase(Locale.ROOT)),
                editDistance(needle, b.toLowerCase(Locale.ROOT))));
        return List.copyOf(matches);
    }

    /** 经典 Levenshtein 距离（大小写由调用方处理）。 */
    public static int editDistance(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        if (left.equals(right)) {
            return 0;
        }
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * 估算聊天栏显示宽度。服务端没有客户端字体，因此按“半角 6px / 全角 9px”近似；
     * 只用于把候选排成多列，不用于精确排版。
     */
    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += isWide(codePoint) ? 9 : 6;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE4F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }

    /** 按显示宽度截断（保留结尾省略号），用于把过长的候选塞进一列。 */
    public static String truncate(String text, int maxWidth) {
        if (text == null || text.isEmpty() || displayWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        int ellipsisWidth = displayWidth("…");
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = isWide(codePoint) ? 9 : 6;
            if (width + charWidth + ellipsisWidth > maxWidth) {
                break;
            }
            out.appendCodePoint(codePoint);
            width += charWidth;
            i += Character.charCount(codePoint);
        }
        return out + "…";
    }

    // ---- Brigadier 适配 ----

    /** 普通参数：用当前 remaining 做前缀过滤，替换范围就是 Brigadier 给的范围。 */
    public static CompletableFuture<Suggestions> offer(SuggestionsBuilder builder,
                                                       Collection<String> candidates) {
        return offer(builder, candidates, null);
    }

    public static CompletableFuture<Suggestions> offer(SuggestionsBuilder builder,
                                                       Collection<String> candidates,
                                                       Function<String, String> tooltip) {
        String remaining = builder.getRemaining();
        List<String> filtered = filter(remaining, candidates);
        return build(builder, builder.getStart(), filtered, tooltip);
    }

    /** 贪心参数：替换范围从 {@code rangeStart} 到末尾。 */
    public static CompletableFuture<Suggestions> offerRange(SuggestionsBuilder builder,
                                                            int rangeStart,
                                                            Collection<String> candidates,
                                                            Function<String, String> tooltip) {
        return build(builder, rangeStart, candidates, tooltip);
    }

    /** 整段 remaining 作为替换范围（命令路径这种本身含空格的候选）。 */
    public static CompletableFuture<Suggestions> offerWholeRemaining(SuggestionsBuilder builder,
                                                                     Collection<String> candidates,
                                                                     Function<String, String> tooltip) {
        return build(builder, builder.getStart(), candidates, tooltip);
    }

    private static CompletableFuture<Suggestions> build(SuggestionsBuilder builder, int rangeStart,
                                                        Collection<String> candidates,
                                                        Function<String, String> tooltip) {
        int end = builder.getStart() + builder.getRemaining().length();
        int start = Math.max(builder.getStart(), Math.min(rangeStart, end));
        StringRange range = StringRange.between(start, end);
        List<Suggestion> suggestions = new ArrayList<>();
        if (candidates != null) {
            for (String candidate : dedupe(candidates)) {
                String tip = tooltip == null ? null : tooltip.apply(candidate);
                suggestions.add(new Suggestion(range, candidate,
                        tip == null || tip.isBlank() ? null : Component.literal(tip)));
            }
        }
        return CompletableFuture.completedFuture(new Suggestions(range, suggestions));
    }
}
