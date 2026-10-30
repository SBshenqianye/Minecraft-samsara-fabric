package com.shenqianye.samsara.config;

/** 字段级配置校验错误（错误信息直接展示在 Web 表单对应字段下）。 */
public class ConfigValidationException extends Exception {
    private final String path;

    public ConfigValidationException(String path, String message) {
        super(message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
