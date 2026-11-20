package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/server` 路由：本体配置、备份、RCON、任务与玩家列表。 */
final class ServerWebHandler {

    private final LocalWebServer host;
    private final ServerWebService serverService;

    ServerWebHandler(LocalWebServer host, ServerWebService serverService) {
        this.host = host;
        this.serverService = serverService;
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
            if (serverService == null) {
                status = 503;
                WebHttp.json(exchange, status, WebHttp.error("本体服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Map.of();
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    body = host.parseObject(host.readBody(exchange));
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                } catch (RuntimeException e) {
                    status = 400;
                    WebHttp.json(exchange, status, WebHttp.error("请求体应为 JSON 对象"));
                    return;
                }
            }
            Object result;
            if ("GET".equals(method)) {
                if ("/api/server".equals(path) || "/api/server/config".equals(path)
                        || "/api/server/backups".equals(path)) {
                    result = serverService.model();
                } else if ("/api/server/jobs".equals(path)) {
                    result = serverService.jobs();
                } else if ("/api/server/players".equals(path)) {
                    result = serverService.players();
                } else if ("/api/server/backups/download".equals(path)) {
                    String id = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery()).get("id");
                    var file = serverService.backupFile(id);
                    if (file == null) {
                        status = 404;
                        WebHttp.json(exchange, status, WebHttp.error("备份不存在"));
                        return;
                    }
                    WebHttp.sendFile(exchange, 200, "application/zip", id + ".zip", file);
                    return;
                } else {
                    status = 404;
                    result = WebHttp.error("Not Found");
                }
            } else if ("POST".equals(method)) {
                switch (path) {
                    case "/api/server/config" -> result = serverService.saveConfig(body);
                    case "/api/server/backups/preview" -> result = serverService.previewCreate();
                    case "/api/server/backups" -> result = serverService.createBackup(body);
                    case "/api/server/backups/delete" -> result = serverService.deleteBackup(body);
                    case "/api/server/backups/preview-restore" ->
                            result = serverService.previewRestore(body);
                    case "/api/server/backups/restore" -> result = serverService.restore(body);
                    case "/api/server/console" -> result = serverService.console(body);
                    case "/api/server/console/complete" -> result = serverService.completeConsole(body);
                    default -> {
                        status = 404;
                        result = WebHttp.error("Not Found");
                    }
                }
            } else {
                status = 405;
                result = WebHttp.error("Method Not Allowed");
            }
            if (status == 200 && WebHttp.failed(result)) {
                status = 400;
            }
            WebHttp.json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error("服务器内部错误"));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
