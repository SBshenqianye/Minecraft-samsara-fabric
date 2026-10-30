package com.shenqianye.samsara.output;

/** 变量类型，仅用于 Web 展示与预览提示；变量值最终都按字面文本插入。 */
public enum OutputVariableType {
    TEXT("text"),
    NUMBER("number"),
    UUID("uuid");

    private final String id;

    OutputVariableType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
