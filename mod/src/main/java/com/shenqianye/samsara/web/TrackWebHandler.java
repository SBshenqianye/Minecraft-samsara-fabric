package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/track` 路由：只读轨迹列表。 */
final class TrackWebHandler {

    private final LocalWebServer host;
    private final TrackWebService trackService;

    TrackWebHandler(LocalWebServer host, TrackWebService trackService) {
        this.host = host;
        this.trackService = trackService;
    }

    void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            int auth = host.authorize(exchange);
            if (auth != 0) {
                status = auth;
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                WebHttp.json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            if ("/api/track".equals(path) || "/api/track/list".equals(path)) {
                Map<String, Object> result = trackService.list(
                        WebHttp.parseQuery(exchange.getRequestURI().getRawQuery()));
                status = result.get("ok") == Boolean.TRUE ? 200
                        : result.get("status") instanceof Number number ? number.intValue() : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, Map.of("error", "未知轨迹接口"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, Map.of("error", WebHttp.errorDetail(e)));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
