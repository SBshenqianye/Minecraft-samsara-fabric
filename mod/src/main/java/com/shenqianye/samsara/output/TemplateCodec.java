package com.shenqianye.samsara.output;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化片段的解析、规范序列化与语义校验。
 *
 * <p>本类不依赖 Minecraft，Web API、YAML 配置与单测共用同一套规则。
 */
public final class TemplateCodec {
    public static final int MAX_PARTS = 64;
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_TOTAL_TEXT = 8192;

    private TemplateCodec() {
    }

    /** 规范化为 output.yml 中的 List&lt;Map&gt; 结构。 */
    public static List<Map<String, Object>> toConfig(List<TemplatePart> parts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TemplatePart part : parts) {
            Map<String, Object> item = new LinkedHashMap<>();
            switch (part) {
                case TextPart text -> item.put("text", text.text());
                case PrefixPart prefix -> item.put("prefix", prefix.text());
                case VariablePart variable -> item.put("var", variable.name());
                case ButtonPart button -> {
                    item.put("button", button.key());
                    if (!button.label().isBlank()) {
                        item.put("label", button.label());
                    }
                }
            }
            out.add(item);
        }
        return out;
    }

    /** 解析任意配置/请求值；失败抛字段级异常。 */
    public static List<TemplatePart> parse(Object raw) throws FormatException {
        if (!(raw instanceof List<?> list)) {
            throw new FormatException("parts", "应为片段数组");
        }
        if (list.size() > MAX_PARTS) {
            throw new FormatException("parts", "片段过多（上限 " + MAX_PARTS + " 个）");
        }
        List<TemplatePart> parts = new ArrayList<>();
        int totalText = 0;
        for (int i = 0; i < list.size(); i++) {
            String field = "parts[" + i + "]";
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                throw new FormatException(field, "片段应为对象");
            }
            String text = null;
            String prefix = null;
            String var = null;
            String button = null;
            String label = null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                switch (key) {
                    case "text" -> text = stringValue(value, field + ".text");
                    case "prefix" -> prefix = stringValue(value, field + ".prefix");
                    case "var" -> var = stringValue(value, field + ".var");
                    case "button" -> button = stringValue(value, field + ".button");
                    case "label" -> label = stringValue(value, field + ".label");
                    default -> throw new FormatException(field, "未知片段字段：" + key);
                }
            }
            int primary = (text == null ? 0 : 1) + (prefix == null ? 0 : 1)
                    + (var == null ? 0 : 1) + (button == null ? 0 : 1);
            if (primary != 1) {
                throw new FormatException(field,
                        "每个片段必须且只能包含 text、prefix、var、button 之一");
            }
            if (text != null) {
                if (label != null) {
                    throw new FormatException(field + ".label", "只有按钮片段可以设置 label");
                }
                if (text.length() > MAX_TEXT_LENGTH) {
                    throw new FormatException(field + ".text",
                            "文本过长（上限 " + MAX_TEXT_LENGTH + " 字符）");
                }
                totalText += text.length();
                parts.add(new TextPart(text));
            } else if (prefix != null) {
                if (label != null) {
                    throw new FormatException(field + ".label", "只有按钮片段可以设置 label");
                }
                if (prefix.length() > MAX_TEXT_LENGTH) {
                    throw new FormatException(field + ".prefix",
                            "前缀过长（上限 " + MAX_TEXT_LENGTH + " 字符）");
                }
                totalText += prefix.length();
                parts.add(new PrefixPart(prefix));
            } else if (var != null) {
                if (label != null) {
                    throw new FormatException(field + ".label", "只有按钮片段可以设置 label");
                }
                if (var.isBlank()) {
                    throw new FormatException(field + ".var", "变量名不能为空");
                }
                parts.add(new VariablePart(var.trim()));
            } else {
                if (button.isBlank()) {
                    throw new FormatException(field + ".button", "按钮 key 不能为空");
                }
                parts.add(new ButtonPart(button.trim(), label == null ? "" : label));
            }
        }
        if (totalText > MAX_TOTAL_TEXT) {
            throw new FormatException("parts", "文本总量过长（上限 " + MAX_TOTAL_TEXT + " 字符）");
        }
        return parts;
    }

    /** 宽松解析；失败返回 null。 */
    public static List<TemplatePart> tryParse(Object raw) {
        try {
            return parse(raw);
        } catch (FormatException e) {
            return null;
        }
    }

    private static String stringValue(Object value, String field) throws FormatException {
        if (value == null) {
            throw new FormatException(field, "值不能为空");
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new FormatException(field, "应为文本");
    }

    /** 语义校验：变量/按钮必须来自后端；required 项不能缺失；同一变量/按钮不能重复。 */
    public static Map<String, String> validate(OutputTemplate template, List<TemplatePart> parts) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (parts == null) {
            errors.put("parts", "缺少模板片段");
            return errors;
        }
        if (parts.isEmpty()) {
            errors.put("parts", "模板至少需要一个片段");
            return errors;
        }
        if (parts.size() > MAX_PARTS) {
            errors.put("parts", "片段过多（上限 " + MAX_PARTS + " 个）");
        }
        Set<String> presentVariables = new HashSet<>();
        Set<String> presentButtons = new HashSet<>();
        List<String> missingVariables = new ArrayList<>();
        int totalText = 0;
        for (int i = 0; i < parts.size(); i++) {
            TemplatePart part = parts.get(i);
            String field = "parts[" + i + "]";
            if (part instanceof TextPart text) {
                totalText += text.text().length();
                if (text.text().length() > MAX_TEXT_LENGTH) {
                    errors.put(field + ".text",
                            "文本过长（上限 " + MAX_TEXT_LENGTH + " 字符）");
                }
            } else if (part instanceof PrefixPart prefix) {
                totalText += prefix.text().length();
                if (prefix.text().length() > MAX_TEXT_LENGTH) {
                    errors.put(field + ".prefix",
                            "前缀过长（上限 " + MAX_TEXT_LENGTH + " 字符）");
                }
            } else if (part instanceof VariablePart variable) {
                if (template.variable(variable.name()) == null) {
                    errors.put(field + ".var", "未知变量：" + variable.name());
                } else {
                    // 同一变量允许重复出现；required 只要求至少出现一次。
                    presentVariables.add(variable.name());
                }
            } else if (part instanceof ButtonPart button) {
                OutputButton definition = template.button(button.key());
                if (definition == null) {
                    errors.put(field + ".button", "未知按钮：" + button.key());
                } else {
                    presentButtons.add(button.key());
                }
            }
        }
        if (totalText > MAX_TOTAL_TEXT) {
            errors.put("parts", "文本总量过长（上限 " + MAX_TOTAL_TEXT + " 字符）");
        }
        for (OutputVariable variable : template.variables()) {
            if (variable.required() && !presentVariables.contains(variable.key())) {
                missingVariables.add(variable.key());
            }
        }
        if (!missingVariables.isEmpty()) {
            errors.put("parts", "缺少必需变量：" + String.join(", ", missingVariables));
        }
        List<String> missingButtons = new ArrayList<>();
        for (OutputButton button : template.buttons()) {
            if (button.required() && !presentButtons.contains(button.key())) {
                missingButtons.add(button.label());
            }
        }
        if (!missingButtons.isEmpty()) {
            errors.put("parts", "缺少必需按钮：" + String.join(", ", missingButtons));
        }
        return errors;
    }

    /** 解析失败的字段级错误。 */
    public static final class FormatException extends Exception {
        private final String field;

        public FormatException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
