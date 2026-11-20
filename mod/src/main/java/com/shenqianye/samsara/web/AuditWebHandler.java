package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** `/api/audit` 路由：Web 写请求审计列表、预览与回滚。 */
final class AuditWebHandler {

    private final LocalWebServer host;

    AuditWebHandler(LocalWebServer host) {
        this.host = host;
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
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                int offset = Math.max(0, WebHttp.intParam(query, "offset", 0));
                int limit = Math.max(1, Math.min(100, WebHttp.intParam(query, "limit", 30)));
                List<Map<String, Object>> entries = WebAuditService.get().listPage(offset, limit);
                int total = WebAuditService.get().total();
                WebHttp.json(exchange, 200, new AuditPage(true, entries, offset, limit, total,
                        offset + entries.size() < total));
                return;
            }
            if (!"POST".equals(method)) {
                status = 405;
                WebHttp.json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            Map<String, Object> body;
            try {
                body = host.parseObject(host.readBody(exchange));
            } catch (LocalWebServer.BodyTooLarge e) {
                status = 413;
                WebHttp.json(exchange, status, Map.of("error", "请求体过大"));
                return;
            } catch (RuntimeException e) {
                status = 400;
                WebHttp.json(exchange, status, Map.of("error", "请求体应为 JSON 对象"));
                return;
            }
            String id = body.get("id") == null ? "" : String.valueOf(body.get("id"));
            Map<String, Object> result;
            if ("/api/audit/preview".equals(path)) {
                result = WebAuditService.get().preview(id);
            } else if ("/api/audit/rollback".equals(path)) {
                result = WebAuditService.get().rollback(id,
                        Boolean.TRUE.equals(body.get("confirm"))
                                || "true".equalsIgnoreCase(String.valueOf(body.get("confirm"))));
            } else {
                status = 404;
                result = Map.of("error", "Not Found");
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) {
                status = 400;
            }
            WebHttp.json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, Map.of("error", "服务器内部错误：" + WebHttp.errorDetail(e)));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
