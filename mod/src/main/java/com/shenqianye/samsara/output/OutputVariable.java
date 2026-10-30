package com.shenqianye.samsara.output;

/** 一条消息可用的变量。变量名是后端唯一事实来源，用户不能新增或改名。 */
public record OutputVariable(String key, String label, String sample, boolean required,
                             OutputVariableType type) {
    public OutputVariable {
        key = key == null ? "" : key;
        label = label == null ? key : label;
        sample = sample == null ? "" : sample;
        type = type == null ? OutputVariableType.TEXT : type;
    }
}
