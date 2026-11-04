package com.shenqianye.samsara.web;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版物品图标白名单：只把确实打包在 {@code /web/items/<id>.png} 的图标发给前端，
 * 避免大量 404 请求。图标在构建时从 Minecraft 资源提取，数量有限。
 */
final class WebIcons {
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private WebIcons() {
    }

    static boolean exists(String icon) {
        if (icon == null || icon.isBlank() || icon.contains("/") || icon.contains("\\")) {
            return false;
        }
        return CACHE.computeIfAbsent(icon, name -> {
            try (InputStream in = WebIcons.class.getResourceAsStream("/web/items/" + name + ".png")) {
                return in != null;
            } catch (Exception e) {
                return false;
            }
        });
    }

    static String orEmpty(String icon) {
        return exists(icon) ? icon : "";
    }
}
