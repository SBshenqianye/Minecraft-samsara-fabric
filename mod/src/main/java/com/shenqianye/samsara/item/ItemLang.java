package com.shenqianye.samsara.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品中文名查询表，数据源是随 Web 资源打包的 {@code /web/item-lang.json}。
 *
 * <p>只保留 {@code zh} 一列，避免为了 tooltip 引入经济模块的依赖；解析一次后常驻内存。
 * 资源缺失时返回空串，调用方回退到服务端名称或物品 ID。
 */
public final class ItemLang {
    private static final Map<String, String> ZH = load();

    private ItemLang() {
    }

    /** 返回物品中文名；未收录时返回空串。 */
    public static String zh(String itemId) {
        if (itemId == null || itemId.isBlank()) return "";
        String value = ZH.get(itemId);
        if (value != null) return value;
        int colon = itemId.indexOf(':');
        return colon >= 0 ? ZH.getOrDefault("minecraft:" + itemId.substring(colon + 1), "") : "";
    }

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = ItemLang.class.getResourceAsStream("/web/item-lang.json")) {
            if (in == null) return out;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            if (items == null) return out;
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                JsonObject row = entry.getValue().getAsJsonObject();
                JsonElement zh = row.get("zh");
                if (zh != null && !zh.isJsonNull()) out.put(entry.getKey(), zh.getAsString());
            }
        } catch (Exception ignored) {
            // 中文名是展示增强，读不到不影响物品读写。
        }
        return out;
    }
}
