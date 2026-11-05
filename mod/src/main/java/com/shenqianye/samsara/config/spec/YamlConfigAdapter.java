package com.shenqianye.samsara.config.spec;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 第三方 YAML 适配器；读取完整文档，写入时保留未知键（注释不保留）。 */
public final class YamlConfigAdapter implements ConfigAdapter {
    @Override
    public ConfigFormat format() {
        return ConfigFormat.YAML;
    }

    @Override
    public boolean nestedPaths() {
        return true;
    }

    @Override
    public Map<String, Object> read(Path file) throws IOException {
        String text = ConfigFileIO.read(file);
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(50);
            options.setCodePointLimit(2_000_000);
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (loaded instanceof Map<?, ?>) {
                return normalizeMap(loaded);
            }
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("value", ConfigPaths.normalize(loaded));
            return wrapped;
        } catch (RuntimeException e) {
            throw new IOException("YAML 解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void write(Path file, Map<String, Object> updates) throws IOException {
        String original = ConfigFileIO.read(file);
        boolean topLevelValue = !original.isBlank()
                && !original.stripLeading().startsWith("-")
                && !original.stripLeading().startsWith("{")
                && !original.contains(":");
        Map<String, Object> document = read(file);
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            ConfigPaths.set(document, e.getKey(), e.getValue());
        }
        boolean wrappedValue = topLevelValue && document.size() == 1 && document.containsKey("value");
        Object root = wrappedValue ? document.get("value") : document;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        Representer representer = new Representer(options);
        String text = new Yaml(representer, options).dump(root);
        ConfigFileIO.atomicWrite(file, text);
    }

    private static Map<String, Object> normalizeMap(Object loaded) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(loaded instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), ConfigPaths.normalize(e.getValue()));
        }
        return out;
    }
}
