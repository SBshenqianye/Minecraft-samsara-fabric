package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;

/**
 * 静态资源（Vue 构建产物、图标、纹理）响应。
 *
 * <p>从 {@code LocalWebServer} 抽出：这里只做“路径 → 资源 → Content-Type”，
 * 鉴权、日志和路由仍由 {@link LocalWebServer} 负责。
 */
final class StaticContentHandler {

    private StaticContentHandler() {
    }

    /**
     * 处理一次静态资源请求。
     *
     * @return 实际写出的 HTTP 状态码（调用方用于访问日志）
     */
    static int serve(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            WebHttp.sendText(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return 405;
        }
        String resource = WebSecurity.staticResource(exchange.getRequestURI().getPath());
        if (resource == null) {
            WebHttp.sendText(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return 404;
        }
        byte[] body;
        try (InputStream in = StaticContentHandler.class.getResourceAsStream(resource)) {
            if (in == null) {
                WebHttp.sendText(exchange, 404, "text/plain; charset=utf-8", "Not Found");
                return 404;
            }
            body = in.readAllBytes();
        }
        String type = resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                : resource.endsWith(".css") ? "text/css; charset=utf-8"
                : resource.endsWith(".png") ? "image/png"
                : resource.endsWith(".json") ? "application/json; charset=utf-8"
                : "text/html; charset=utf-8";
        WebHttp.sendBytes(exchange, 200, type, body);
        return 200;
    }
}
