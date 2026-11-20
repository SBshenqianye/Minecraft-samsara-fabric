package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/permissions` 路由：权限树读取、保存与重载。 */
final class PermissionsWebHandler {

    private final LocalWebServer host;
    private final PermissionWebService permissionService;

    PermissionsWebHandler(LocalWebServer host, PermissionWebService permissionService) {
        this.host = host;
        this.permissionService = permissionService;
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
            if (permissionService == null) {
                status = 503;
                WebHttp.json(exchange, status, Map.of("error", "权限树服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Map.of();
            if ("PUT".equals(method) || "POST".equals(method)) {
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
            }
            Map<String, Object> result;
            switch (path) {
                case "/api/permissions" -> {
                    if ("GET".equals(method)) {
                        result = permissionService.model();
                    } else if ("PUT".equals(method)) {
                        result = permissionService.save(body);
                    } else {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    }
                }
                case "/api/permissions/reload" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = Map.of("error", "Method Not Allowed");
                    } else {
                        result = permissionService.reload();
                    }
                }
                default -> {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            }
            if (status == 200 && Boolean.FALSE.equals(result.get("ok"))) {
                status = 400;
            }
            WebHttp.json(exchange, status, result);
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, Map.of("error", "服务器内部错误"));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
