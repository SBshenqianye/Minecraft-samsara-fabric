package com.shenqianye.samsara.config.spec;

/** 表单分组（仅用于导航与排序）。 */
public record FormGroupSpec(String id, String label) {
    public FormGroupSpec {
        id = id == null ? "" : id;
        label = label == null || label.isBlank() ? id : label;
    }
}
