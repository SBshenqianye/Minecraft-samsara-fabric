package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/diff` 路由：区块差异摘要、差异回看与恢复。 */
final class DiffWebHandler {

    private final LocalWebServer host;
    private final ChunkDiffWebService diffService;

    DiffWebHandler(LocalWebServer host, ChunkDiffWebService diffService) {
        this.host = host;
        this.diffService = diffService;
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
            if (diffService == null) {
                status = 503;
                WebHttp.json(exchange, status, Map.of("error", "Diff 服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
            if ("GET".equals(method)) {
                if ("/api/diff/chunk".equals(path)) {
                    Integer cx = WebHttp.intParam(query.get("cx"));
                    Integer cz = WebHttp.intParam(query.get("cz"));
                    boolean load = "1".equals(query.get("load"));
                    WebHttp.json(exchange, 200, diffService.summary(
                            query.getOrDefault("stack", ""), query.getOrDefault("dim", ""),
                            cx, cz, query.get("to"), load));
                    return;
                }
                if ("/api/diff/view".equals(path) || "/api/diff/view/status".equals(path)) {
                    WebHttp.json(exchange, 200, diffService.viewStatus());
                    return;
                }
                status = 404;
                WebHttp.json(exchange, status, Map.of("error", "Not Found"));
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
            if ("/api/diff/chunk/restore".equals(path)) {
                WebHttp.json(exchange, 200, diffService.restore(body));
                return;
            }
            if ("/api/diff/view".equals(path)) {
                WebHttp.json(exchange, 200, diffService.view(body));
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, Map.of("error", "Not Found"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, Map.of("error", "服务器内部错误"));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
