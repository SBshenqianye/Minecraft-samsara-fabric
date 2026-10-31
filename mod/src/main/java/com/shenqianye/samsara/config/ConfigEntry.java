package com.shenqianye.samsara.config;

import java.util.List;

/**
 * 一个配置项的定义（schema 的唯一单元）。
 *
 * @param path        点分路径，如 {@code view.aim.enabled}
 * @param type        类型
 * @param defaultValue 默认值（集合/映射会被深拷贝）
 * @param min         数值下限（可空）
 * @param max         数值上限（可空）
 * @param choices     枚举可选项（其他类型为空）
 * @param description 说明，用于生成注释与 Web 提示
 * @param group       页面分组
 * @param restart     修改后是否需要重启（Web 显示）
 */
public record ConfigEntry(String path, ConfigType type, Object defaultValue,
                          Double min, Double max, List<String> choices,
                          String description, String group, boolean restart) {

    public ConfigEntry {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static ConfigEntry bool(String path, boolean defaultValue, String group,
                                   String description) {
        return new ConfigEntry(path, ConfigType.BOOLEAN, defaultValue, null, null,
                List.of(), description, group, false);
    }

    public static ConfigEntry integer(String path, int defaultValue, int min, int max,
                                      String group, String description) {
        return new ConfigEntry(path, ConfigType.INT, defaultValue, (double) min, (double) max,
                List.of(), description, group, false);
    }

    public static ConfigEntry decimal(String path, double defaultValue, double min, double max,
                                      String group, String description) {
        return new ConfigEntry(path, ConfigType.DOUBLE, defaultValue, min, max,
                List.of(), description, group, false);
    }

    public static ConfigEntry string(String path, String defaultValue, String group,
                                     String description) {
        return new ConfigEntry(path, ConfigType.STRING, defaultValue, null, null,
                List.of(), description, group, false);
    }

    public static ConfigEntry choice(String path, String defaultValue, List<String> choices,
                                     String group, String description) {
        return new ConfigEntry(path, ConfigType.ENUM, defaultValue, null, null,
                choices, description, group, false);
    }

    public static ConfigEntry stringList(String path, List<String> defaultValue, String group,
                                         String description) {
        return new ConfigEntry(path, ConfigType.STRING_LIST, List.copyOf(defaultValue), null, null,
                List.of(), description, group, false);
    }

    /** 定长整数表（如 16 种染料颜色 → 距离）；Web 用可视化控件编辑。 */
    public static ConfigEntry intList(String path, List<Integer> defaultValue, int min, int max,
                                      String group, String description) {
        return new ConfigEntry(path, ConfigType.INT_LIST, List.copyOf(defaultValue),
                (double) min, (double) max, List.of(), description, group, false);
    }

    public static ConfigEntry levelMap(String path, java.util.Map<String, String> defaultValue,
                                       String group, String description) {
        return new ConfigEntry(path, ConfigType.LEVEL_MAP,
                new java.util.LinkedHashMap<>(defaultValue), null, null,
                List.of(), description, group, false);
    }

    /** 结构化输出模板：有序片段列表。 */
    public static ConfigEntry template(String path, java.util.List<java.util.Map<String, Object>> defaultValue,
                                       String group, String description) {
        return new ConfigEntry(path, ConfigType.TEMPLATE, defaultValue, null, null,
                List.of(), description, group, false);
    }
}
