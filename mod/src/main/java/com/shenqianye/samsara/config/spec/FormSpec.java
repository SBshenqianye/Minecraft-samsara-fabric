package com.shenqianye.samsara.config.spec;

import java.util.List;

/** 一个配置文件的字段集合。 */
public record FormSpec(String id, List<FormGroupSpec> groups, List<FormFieldSpec> fields) {
    public FormSpec {
        id = id == null ? "" : id;
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
