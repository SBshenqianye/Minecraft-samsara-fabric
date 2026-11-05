package com.shenqianye.samsara.config.spec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 第三方 JSON 适配器；保留未知键与对象插入顺序。 */
public final class JsonConfigAdapter implements ConfigAdapter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public ConfigFormat format() {
        return ConfigFormat.JSON;
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
            JsonElement parsed = JsonParser.parseString(text);
            Object value = toJava(parsed);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", value);
            return out;
        } catch (RuntimeException e) {
            throw new IOException("JSON 解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void write(Path file, Map<String, Object> updates) throws IOException {
        String original = ConfigFileIO.read(file).trim();
        boolean topLevelValue = !original.isEmpty() && !original.startsWith("{");
        Map<String, Object> document = read(file);
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            ConfigPaths.set(document, e.getKey(), e.getValue());
        }
        Object root = topLevelValue && document.containsKey("value") ? document.get("value") : document;
        ConfigFileIO.atomicWrite(file, GSON.toJson(toJson(root)) + "\n");
    }

    private static Object toJava(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                out.put(e.getKey(), toJava(e.getValue()));
            }
            return out;
        }
        if (element.isJsonArray()) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                out.add(toJava(item));
            }
            return out;
        }
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            String raw = element.getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return element.getAsDouble();
            }
            try {
                return element.getAsLong();
            } catch (NumberFormatException e) {
                return element.getAsDouble();
            }
        }
        return element.getAsString();
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                object.add(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return object;
        }
        if (value instanceof Iterable<?> list) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (Object item : list) {
                array.add(toJson(item));
            }
            return array;
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new com.google.gson.JsonPrimitive(bool);
        }
        return new com.google.gson.JsonPrimitive(String.valueOf(value));
    }
}
