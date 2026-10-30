package com.shenqianye.samsara.output;

/**
 * 固定按钮定义。命令不可由用户编辑；style 为 run（点击执行）或 suggest（点击填入）。
 */
public record OutputButton(String key, String label, String command, String hover,
                           String style, String permissionPath, boolean required) {
    public OutputButton {
        key = key == null ? "" : key;
        label = label == null ? key : label;
        command = command == null ? "" : command;
        hover = hover == null ? "" : hover;
        style = style == null || style.isBlank() ? "run" : style;
        permissionPath = permissionPath == null ? "" : permissionPath;
    }
}
