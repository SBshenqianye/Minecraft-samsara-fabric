package com.shenqianye.samsara.output;

import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 输出模板渲染器。Web 预览与游戏内实际输出共用同一套片段渲染，禁止前端另写格式逻辑。
 */
public final class OutputRenderer {
    private static final Map<Character, String> COLORS = new LinkedHashMap<>();
    private static final Map<Character, String> FORMATS = new LinkedHashMap<>();

    static {
        COLORS.put('0', "black");
        COLORS.put('1', "dark_blue");
        COLORS.put('2', "dark_green");
        COLORS.put('3', "dark_aqua");
        COLORS.put('4', "dark_red");
        COLORS.put('5', "dark_purple");
        COLORS.put('6', "gold");
        COLORS.put('7', "gray");
        COLORS.put('8', "dark_gray");
        COLORS.put('9', "blue");
        COLORS.put('a', "green");
        COLORS.put('b', "aqua");
        COLORS.put('c', "red");
        COLORS.put('d', "light_purple");
        COLORS.put('e', "yellow");
        COLORS.put('f', "white");
        FORMATS.put('l', "bold");
        FORMATS.put('o', "italic");
        FORMATS.put('n', "underline");
        FORMATS.put('m', "strikethrough");
    }

    private OutputRenderer() {
    }

    /** 按钮可见/可点击策略。预览默认全部可见可点击；游戏内按权限与配置过滤。 */
    public interface ButtonPolicy {
        boolean visible(OutputButton button);

        boolean enabled(OutputButton button);

        static ButtonPolicy all() {
            return new ButtonPolicy() {
                @Override
                public boolean visible(OutputButton button) {
                    return true;
                }

                @Override
                public boolean enabled(OutputButton button) {
                    return true;
                }
            };
        }
    }

    /** 当前生效模板：配置非法/缺失时回退默认模板。 */
    public static List<TemplatePart> currentParts(String key) {
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return List.of();
        }
        try {
            Object raw = ConfigManager.getInstance().value(ConfigSchema.OUTPUT, key);
            List<TemplatePart> parsed = TemplateCodec.tryParse(raw);
            if (parsed != null && TemplateCodec.validate(template, parsed).isEmpty()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // 配置尚未初始化（单测/早期启动）时直接使用默认模板
        }
        return template.defaultParts();
    }

    /** 纯逻辑渲染：不添加聊天前缀，供同一消息多次渲染后组合。 */
    public static List<OutputPreviewFragment> renderRaw(String key, Map<String, Object> values,
                                                        ButtonPolicy policy) {
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return List.of(OutputPreviewFragment.text("text", "未知输出消息：" + key, "red"));
        }
        return renderRaw(template, currentParts(key), values, policy);
    }

    /** 渲染时跳过 PrefixPart；用于把多条消息拼成一条复合消息。 */
    public static List<OutputPreviewFragment> renderRawBody(String key, Map<String, Object> values,
                                                            ButtonPolicy policy) {
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return List.of();
        }
        List<TemplatePart> body = new ArrayList<>();
        for (TemplatePart part : currentParts(key)) {
            if (!(part instanceof PrefixPart)) {
                body.add(part);
            }
        }
        return renderRaw(template, body, values, policy);
    }

    /** 只渲染 PrefixPart，供复合消息统一放在最前面。 */
    public static List<OutputPreviewFragment> renderPrefix(String key, Map<String, Object> values,
                                                           ButtonPolicy policy) {
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return List.of();
        }
        List<TemplatePart> prefix = new ArrayList<>();
        for (TemplatePart part : currentParts(key)) {
            if (part instanceof PrefixPart) {
                prefix.add(part);
            }
        }
        return renderRaw(template, prefix, values, policy);
    }

    public static List<OutputPreviewFragment> renderRaw(OutputTemplate template,
                                                        List<TemplatePart> parts,
                                                        Map<String, Object> values,
                                                        ButtonPolicy policy) {
        List<OutputPreviewFragment> out = new ArrayList<>();
        StyleState style = new StyleState();
        Map<String, Object> safeValues = values == null ? Map.of() : values;
        ButtonPolicy safePolicy = policy == null ? ButtonPolicy.all() : policy;
        for (TemplatePart part : parts) {
            if (part instanceof TextPart text) {
                appendStyledText(out, text.text(), style);
            } else if (part instanceof PrefixPart prefix) {
                appendStyledText(out, prefix.text(), style);
            } else if (part instanceof VariablePart variable) {
                Object value = safeValues.get(variable.name());
                String rendered = value == null ? "" : String.valueOf(value);
                if (!rendered.isEmpty()) {
                    out.add(new OutputPreviewFragment("variable", rendered, style.color,
                            style.bold, style.italic, style.underline, style.strikethrough,
                            false, "", "", ""));
                }
            } else if (part instanceof ButtonPart buttonPart) {
                OutputButton button = template.button(buttonPart.key());
                if (button == null || !safePolicy.visible(button)) {
                    continue;
                }
                boolean enabled = safePolicy.enabled(button);
                String label = buttonPart.label().isBlank() ? button.label() : buttonPart.label();
                String color = !enabled ? "dark_gray"
                        : "suggest".equals(button.style()) ? "yellow" : "aqua";
                out.add(new OutputPreviewFragment("button", "[" + label + "]", color,
                        false, false, false, false, enabled,
                        enabled ? normalizeCommand(button.command()) : "",
                        enabled ? button.style() : "", button.hover()));
            }
        }
        return out;
    }

    /** 给原始片段加 ChatFormat 首行/续行前缀，并整理首尾空行。 */
    public static List<OutputPreviewFragment> decorate(List<OutputPreviewFragment> raw,
                                                       boolean explicitPrefix) {
        return decorate(raw, explicitPrefix, true);
    }

    /**
     * @param autoContinuePrefix 是否自动给续行加“  ▸ ”前缀；关闭后完全按模板文字输出
     */
    public static List<OutputPreviewFragment> decorate(List<OutputPreviewFragment> raw,
                                                       boolean explicitPrefix,
                                                       boolean autoContinuePrefix) {
        List<List<OutputPreviewFragment>> lines = splitLines(raw);
        while (!lines.isEmpty() && isEmptyLine(lines.get(0))) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && isEmptyLine(lines.get(lines.size() - 1))) {
            lines.remove(lines.size() - 1);
        }
        List<OutputPreviewFragment> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<OutputPreviewFragment> line = lines.get(i);
            if (line.isEmpty()) {
                if (i > 0) {
                    out.add(OutputPreviewFragment.text("text", "\n", null));
                }
                continue;
            }
            if (i > 0) {
                out.add(OutputPreviewFragment.text("text", "\n", null));
            }
            if (i == 0) {
                if (!explicitPrefix) {
                    out.add(OutputPreviewFragment.text("prefix",
                            ChatFormat.getFirstPrefix(), null));
                }
            } else if (autoContinuePrefix) {
                out.add(OutputPreviewFragment.text("prefix",
                        ChatFormat.getContinuePrefix(), null));
            }
            out.addAll(line);
        }
        return out;
    }

    /** 游戏内渲染：加前缀并转换成 Component。 */
    public static MutableComponent render(String key, Map<String, Object> values,
                                          CommandSourceStack source, Predicate<String> extraVisible) {
        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            return Component.literal("未知输出消息：" + key);
        }
        List<TemplatePart> parts = currentParts(key);
        boolean explicitPrefix = hasExplicitPrefix(parts);
        Map<String, Object> safeValues = new LinkedHashMap<>();
        if (values != null) {
            safeValues.putAll(values);
        }
        if (explicitPrefix) {
            safeValues.putIfAbsent("prefix", ChatFormat.getFirstPrefix());
        }
        ButtonPolicy policy = new ButtonPolicy() {
            @Override
            public boolean visible(OutputButton button) {
                return extraVisible == null || extraVisible.test(button.key());
            }

            @Override
            public boolean enabled(OutputButton button) {
                return button.permissionPath().isBlank() || source == null
                        || PermissionChecker.check(source, button.permissionPath());
            }
        };
        List<OutputPreviewFragment> raw = renderRaw(template, parts, safeValues, policy);
        return toComponent(decorate(raw, explicitPrefix, autoContinuePrefix()));
    }

    /** 动态报告类消息：先按旧 ChatFormat 宽度换行，再交给模板系统加前缀/样式。 */
    public static MutableComponent renderLegacy(String key, String message,
                                                CommandSourceStack source) {
        String wrapped = ChatFormat.wrap(message == null ? "" : message,
                ChatFormat.DEFAULT_MAX_WIDTH);
        return render(key, Map.of("message", wrapped), source, null);
    }

    /** Web 预览：用样例值渲染，按钮全部可见可点击（只显示标签，不执行命令）。 */
    public static List<OutputPreviewFragment> preview(String key, List<TemplatePart> parts) {        OutputTemplate template = OutputCatalog.template(key);
        if (template == null) {
            throw new IllegalArgumentException("未知输出消息：" + key);
        }
        boolean explicitPrefix = hasExplicitPrefix(parts);
        Map<String, Object> values = OutputCatalog.sampleValues(key);
        if (explicitPrefix) {
            values.putIfAbsent("prefix", ChatFormat.getFirstPrefix());
        }
        return decorate(renderRaw(template, parts, values, ButtonPolicy.all()), explicitPrefix,
                autoContinuePrefix());
    }

    public static boolean hasExplicitPrefix(List<TemplatePart> parts) {
        for (TemplatePart part : parts) {
            if (part instanceof PrefixPart) {
                return true;
            }
            if (part instanceof VariablePart variable && "prefix".equals(variable.name())) {
                return true;
            }
        }
        return false;
    }

    public static String plainText(List<OutputPreviewFragment> fragments) {
        StringBuilder out = new StringBuilder();
        for (OutputPreviewFragment fragment : fragments) {
            out.append(fragment.text());
        }
        return out.toString();
    }

    public static MutableComponent toComponent(List<OutputPreviewFragment> fragments) {
        MutableComponent out = Component.empty();
        for (OutputPreviewFragment fragment : fragments) {
            if (fragment.text().isEmpty()) {
                continue;
            }
            Style style = Style.EMPTY;
            ChatFormatting color = color(fragment.color());
            if (color != null) {
                style = style.withColor(color);
            }
            if (fragment.bold()) {
                style = style.withBold(true);
            }
            if (fragment.italic()) {
                style = style.withItalic(true);
            }
            if (fragment.underline()) {
                style = style.withUnderlined(true);
            }
            if (fragment.strikethrough()) {
                style = style.withStrikethrough(true);
            }
            if (fragment.clickable() && !fragment.clickCommand().isBlank()) {
                ClickEvent click = "suggest".equals(fragment.clickMode())
                        ? new ClickEvent.SuggestCommand(fragment.clickCommand())
                        : new ClickEvent.RunCommand(fragment.clickCommand());
                style = style.withClickEvent(click);
            }
            if (!fragment.hover().isBlank()) {
                style = style.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(fragment.hover())));
            }
            out.append(Component.literal(fragment.text()).setStyle(style));
        }
        return out;
    }

    /** 纯渲染单测/早期启动时配置可能尚未就绪，回退为开启。 */
    private static boolean autoContinuePrefix() {
        try {
            return CommandConfig.getInstance().outputAutoContinuePrefix();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static ChatFormatting color(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ChatFormatting.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeCommand(String command) {
        String value = command == null ? "" : command.trim();
        if (value.isEmpty()) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static void appendStyledText(List<OutputPreviewFragment> out, String text,
                                         StyleState style) {
        StringBuilder buffer = new StringBuilder();
        String currentColor = style.color;
        boolean currentBold = style.bold;
        boolean currentItalic = style.italic;
        boolean currentUnderline = style.underline;
        boolean currentStrike = style.strikethrough;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '&' || i + 1 >= text.length()) {
                buffer.append(c);
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            String color = COLORS.get(code);
            String format = FORMATS.get(code);
            if (color == null && format == null && code != 'r') {
                // 未知颜色代码按普通文字处理
                buffer.append(c);
                continue;
            }
            flush(out, buffer, currentColor, currentBold, currentItalic,
                    currentUnderline, currentStrike);
            if (color != null) {
                style.color = color;
            } else if (format != null) {
                switch (format) {
                    case "bold" -> style.bold = true;
                    case "italic" -> style.italic = true;
                    case "underline" -> style.underline = true;
                    case "strikethrough" -> style.strikethrough = true;
                    default -> {
                    }
                }
            } else {
                style.reset();
            }
            currentColor = style.color;
            currentBold = style.bold;
            currentItalic = style.italic;
            currentUnderline = style.underline;
            currentStrike = style.strikethrough;
            i++;
        }
        flush(out, buffer, currentColor, currentBold, currentItalic, currentUnderline, currentStrike);
    }

    private static void flush(List<OutputPreviewFragment> out, StringBuilder buffer, String color,
                              boolean bold, boolean italic, boolean underline,
                              boolean strikethrough) {
        if (buffer.isEmpty()) {
            return;
        }
        out.add(new OutputPreviewFragment("text", buffer.toString(), color, bold, italic,
                underline, strikethrough, false, "", "", ""));
        buffer.setLength(0);
    }

    private static List<List<OutputPreviewFragment>> splitLines(
            List<OutputPreviewFragment> fragments) {
        List<List<OutputPreviewFragment>> lines = new ArrayList<>();
        List<OutputPreviewFragment> current = new ArrayList<>();
        for (OutputPreviewFragment fragment : fragments) {
            String text = fragment.text();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '\n') {
                    continue;
                }
                if (i > start) {
                    current.add(withText(fragment, text.substring(start, i)));
                }
                lines.add(current);
                current = new ArrayList<>();
                start = i + 1;
            }
            if (start < text.length()) {
                current.add(withText(fragment, text.substring(start)));
            }
        }
        lines.add(current);
        return lines;
    }

    private static OutputPreviewFragment withText(OutputPreviewFragment fragment, String text) {
        return new OutputPreviewFragment(fragment.kind(), text, fragment.color(), fragment.bold(),
                fragment.italic(), fragment.underline(), fragment.strikethrough(),
                fragment.clickable(), fragment.clickCommand(), fragment.clickMode(),
                fragment.hover());
    }

    private static boolean isEmptyLine(List<OutputPreviewFragment> line) {
        for (OutputPreviewFragment fragment : line) {
            if (!fragment.text().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static final class StyleState {
        String color;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strikethrough;

        void reset() {
            color = null;
            bold = false;
            italic = false;
            underline = false;
            strikethrough = false;
        }
    }
}
