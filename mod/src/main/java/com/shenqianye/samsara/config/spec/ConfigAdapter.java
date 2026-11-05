package com.shenqianye.samsara.config.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** 第三方配置文件适配器。 */
public interface ConfigAdapter {
    ConfigFormat format();

    /** true 表示字段路径按点分层（YAML/JSON/TOML）；false 表示 properties 的键就是扁平键。 */
    boolean nestedPaths();

    /** 读取完整文档（未知键必须保留在返回结构中）。 */
    Map<String, Object> read(Path file) throws IOException;

    /** 按扁平路径更新并原子写回；实现必须保留当前文档中的未知键。 */
    void write(Path file, Map<String, Object> updates) throws IOException;
}
