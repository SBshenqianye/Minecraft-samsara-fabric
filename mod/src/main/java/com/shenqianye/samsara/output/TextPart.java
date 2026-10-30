package com.shenqianye.samsara.output;

/** 普通文本片段。可包含 &amp; 颜色代码与实际换行；用户手写的 {player} 只是普通文字。 */
public record TextPart(String text) implements TemplatePart {
    public TextPart {
        text = text == null ? "" : text;
    }

    @Override
    public String type() {
        return "text";
    }
}
