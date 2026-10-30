package com.shenqianye.samsara.output;

/** 首行聊天前缀片段；内容可编辑，续行前缀仍由 ChatFormat 自动补。 */
public record PrefixPart(String text) implements TemplatePart {
    public PrefixPart {
        text = text == null ? "" : text;
    }

    @Override
    public String type() {
        return "prefix";
    }
}
