package com.shenqianye.samsara.config.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * properties/conf/ini 适配器。键就是扁平键（不按点分层），写入采用逐行替换，
 * 保留注释、未知键与原顺序；新键追加到末尾。
 */
public final class PropertiesConfigAdapter implements ConfigAdapter {
    @Override
    public ConfigFormat format() {
        return ConfigFormat.PROPERTIES;
    }

    @Override
    public boolean nestedPaths() {
        return false;
    }

    @Override
    public Map<String, Object> read(Path file) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : ConfigFileIO.read(file).split("\\R", -1)) {
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = indexOfSeparator(trimmed);
            if (eq <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return out;
    }

    @Override
    public void write(Path file, Map<String, Object> updates) throws IOException {
        String text = ConfigFileIO.read(file);
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = indexOfSeparator(trimmed);
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            seen.putIfAbsent(key, i);
        }
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (value.contains("\n") || value.contains("\r")) {
                throw new IOException("properties 值不能包含换行：" + key);
            }
            Integer index = seen.get(key);
            if (index == null) {
                lines.add(key + "=" + value);
                seen.put(key, lines.size() - 1);
            } else {
                String original = lines.get(index);
                int eq = indexOfSeparator(original);
                int keyEnd = eq < 0 ? original.length() : eq;
                String prefix = original.substring(0, keyEnd);
                lines.set(index, prefix + "=" + value);
            }
        }
        String out = String.join("\n", lines);
        if (!out.endsWith("\n")) {
            out += "\n";
        }
        ConfigFileIO.atomicWrite(file, out);
    }

    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static String stripComment(String line) {
        boolean single = false;
        boolean doubled = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !doubled) {
                single = !single;
            } else if (c == '"' && !single) {
                doubled = !doubled;
            } else if (!single && !doubled && (c == '#' || c == '!')) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
