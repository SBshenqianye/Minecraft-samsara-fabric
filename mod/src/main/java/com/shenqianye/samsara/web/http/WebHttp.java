package com.shenqianye.samsara.web.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.web.IdempotencyService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 边界工具：查询串、JSON/文本响应、缓存与安全头、附件下载。
 *
 * <p>从 {@code LocalWebServer} 抽出，避免业务路由与 HTTP 细节混在一个类里；
 * 这里只做无状态转换，不认识任何业务对象。请求体读取仍留在 {@code LocalWebServer}
 * （它需要与 {@code BodyTooLarge} 异常一起使用）。
 */
public final class WebHttp {
    private static final Gson GSON = new GsonBuilder().create();

    private WebHttp() {
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            out.putIfAbsent(key, value);
        }
        return out;
    }

    public static Integer intParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int intParam(Map<String, String> query, String key, int fallback) {
        Integer value = intParam(query.get(key));
        return value == null ? fallback : value;
    }

    /**
     * 本地面板排障用：把异常类型/消息带回前端，避免只看到“服务器内部错误”。
     * 只保留一行并截断，避免把超长堆栈或路径塞进响应。
     */
    public static String errorDetail(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() > 300) {
            singleLine = singleLine.substring(0, 300) + "...";
        }
        return error.getClass().getSimpleName() + ": " + singleLine;
    }

    public static void json(HttpExchange exchange, int status, Object body) throws IOException {
        String text = GSON.toJson(body);
        IdempotencyService.getInstance().store(exchange, status, text);
        sendBytes(exchange, status, "application/json; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    public static void sendText(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        sendBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    public static void sendBytes(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control",
                cacheControl(exchange.getRequestURI().getPath()));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 静态资源/地图瓦片/头像允许浏览器缓存；业务 JSON 仍保持 no-store。 */
    public static String cacheControl(String path) {
        if (path == null) return "no-store";
        if (path.startsWith("/vue/assets/") || path.startsWith("/ui/")
                || path.startsWith("/items/") || path.startsWith("/textures/")) {
            return "public, max-age=31536000, immutable";
        }
        if (path.equals("/api/user/map/head") || path.equals("/api/user/map/avatar")) {
            return "public, max-age=86400";
        }
        if (path.equals("/api/user/map/tile")) {
            return "public, max-age=3600";
        }
        if (path.equals("/api/items/schema")) {
            return "public, max-age=3600";
        }
        return "no-store";
    }

    /**
     * 公网只读/管理面板都可能被扫描：统一补浏览器侧防护头。
     * 脚本全部是同源外部文件，页面没有内联 script / innerHTML，所以 CSP 不需要放开脚本。
     */
    public static void securityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Frame-Options", "SAMEORIGIN");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: blob:; connect-src 'self'; "
                        + "font-src 'self' data:; object-src 'none'; base-uri 'none'; "
                        + "form-action 'self'; frame-ancestors 'self'");
    }

    public static void sendFile(HttpExchange exchange, int status, String type, String name,
                                Path file) throws IOException {
        long size = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + name.replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, size);
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(file)) {
            in.transferTo(out);
        }
    }
}
