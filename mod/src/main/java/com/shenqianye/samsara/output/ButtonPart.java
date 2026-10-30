package com.shenqianye.samsara.output;

/**
 * 按钮插槽。命令由 {@link OutputButton} 固定，用户只能移动/删除，或覆盖显示标签。
 */
public record ButtonPart(String key, String label) implements TemplatePart {
    public ButtonPart(String key) {
        this(key, "");
    }

    public ButtonPart {
        key = key == null ? "" : key;
        label = label == null ? "" : label;
    }

    @Override
    public String type() {
        return "button";
    }
}
