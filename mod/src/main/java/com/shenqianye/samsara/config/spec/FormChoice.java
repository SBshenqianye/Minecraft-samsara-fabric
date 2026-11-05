package com.shenqianye.samsara.config.spec;

/** 枚举字段的单个选项（值 + 显示名）。 */
public record FormChoice(String value, String label) {
    public FormChoice {
        value = value == null ? "" : value;
        label = label == null || label.isBlank() ? value : label;
    }

    public static FormChoice of(String value) {
        return new FormChoice(value, value);
    }
}
