package com.shenqianye.samsara.config.spec;

import java.nio.file.Path;
import java.util.List;

/** 第三方配置适配器注册表。 */
public final class ConfigAdapters {
    private static final List<ConfigAdapter> ADAPTERS = List.of(
            new YamlConfigAdapter(),
            new JsonConfigAdapter(),
            new TomlConfigAdapter(),
            new PropertiesConfigAdapter()
    );

    private ConfigAdapters() {
    }

    public static ConfigAdapter forFile(Path file) {
        ConfigFormat format = ConfigFormat.of(file);
        for (ConfigAdapter adapter : ADAPTERS) {
            if (adapter.format() == format) {
                return adapter;
            }
        }
        return null;
    }

    public static List<ConfigAdapter> all() {
        return ADAPTERS;
    }
}
