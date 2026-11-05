package com.shenqianye.samsara.config.spec;

import java.util.List;

/**
 * 一个可渲染的表单字段。
 *
 * <p>{@code type} 是前端 widget 类型：bool / enum / int / number / text / password /
 * url / multiline / json / custom。它独立于 {@code ConfigType}，因为第三方配置需要
 * 通用 JSON/文本编辑，而 samsara 自有配置还有曲线、模板等专用控件。
 */
public record FormFieldSpec(
        String path,
        String label,
        String type,
        Object defaultValue,
        Double min,
        Double max,
        Double step,
        List<FormChoice> choices,
        String group,
        String description,
        boolean restart,
        String visibleWhen,
        boolean secret,
        boolean readOnly,
        String widget,
        String placeholder) {

    public FormFieldSpec {
        path = path == null ? "" : path;
        label = label == null || label.isBlank() ? path : label;
        type = type == null || type.isBlank() ? "text" : type;
        choices = choices == null ? List.of() : List.copyOf(choices);
        group = group == null || group.isBlank() ? "常规" : group;
        description = description == null ? "" : description;
        widget = widget == null ? "" : widget;
        placeholder = placeholder == null ? "" : placeholder;
    }

    public static FormFieldSpec text(String path, String group, String description) {
        return new FormFieldSpec(path, path, "text", "", null, null, null,
                List.of(), group, description, false, null, false, false, "", "");
    }
}
