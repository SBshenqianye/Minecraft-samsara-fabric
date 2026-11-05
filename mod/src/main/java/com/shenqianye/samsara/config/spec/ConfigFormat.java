package com.shenqianye.samsara.config.spec;

import java.nio.file.Path;
import java.util.Locale;

/** 配置文件格式；第三方适配器按扩展名匹配。 */
public enum ConfigFormat {
    YAML("yaml"),
    JSON("json"),
    TOML("toml"),
    PROPERTIES("properties"),
    UNKNOWN("unknown");

    private final String id;

    ConfigFormat(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 根据扩展名判断格式；未知返回 {@link #UNKNOWN}。 */
    public static ConfigFormat of(Path file) {
        if (file == null || file.getFileName() == null) {
            return UNKNOWN;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return YAML;
        }
        if (name.endsWith(".json")) {
            return JSON;
        }
        if (name.endsWith(".toml")) {
            return TOML;
        }
        if (name.endsWith(".properties") || name.endsWith(".conf") || name.endsWith(".ini")) {
            return PROPERTIES;
        }
        return UNKNOWN;
    }
}
