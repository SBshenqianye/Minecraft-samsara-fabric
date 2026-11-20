package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/**
 * `/api/identity` 路由。
 *
 * <p>从 {@code LocalWebServer} 抽出：只负责路径 → IdentityWebService 的映射，
 * 鉴权/日志/JSON 响应仍由 {@link LocalWebServer} 的上下文方法完成。
 */
final class IdentityWebHandler {

    private final LocalWebServer host;
    private final IdentityWebService identityService;

    IdentityWebHandler(LocalWebServer host, IdentityWebService identityService) {
        this.host = host;
        this.identityService = identityService;
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
            if (identityService == null) {
                status = 503;
                WebHttp.json(exchange, status, WebHttp.error("身份服务未启动"));
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
            switch (path) {
                case "/api/identity" -> {
                    if ("GET".equals(method)) {
                        result = identityService.model();
                    } else if ("PUT".equals(method) || "POST".equals(method)) {
                        result = identityService.save(body);
                    } else {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    }
                }
                case "/api/identity/preview" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.preview(body);
                    }
                }
                case "/api/identity/rebind" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.rebind(body);
                    }
                }
                case "/api/identity/reload" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.reload();
                    }
                }
                case "/api/identity/audit" -> {
                    if (!"GET".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.audit();
                    }
                }
                case "/api/identity/generate" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.generate();
                    }
                }
                case "/api/identity/resolve" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.resolve(body);
                    }
                }
                case "/api/identity/data" -> {
                    if (!"GET".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                        result = inspectData(query.get("uuid"));
                    }
                }
                case "/api/identity/data/backup" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = backupData(body.get("uuid"));
                    }
                }
                case "/api/identity/data/list" -> {
                    if (!"GET".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.dataList();
                    }
                }
                case "/api/identity/data/backups" -> {
                    if (!"GET".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                        result = identityService.dataBackups(query.get("uuid"));
                    }
                }
                case "/api/identity/data/label" -> {
                    if (!"POST".equals(method)) {
                        status = 405;
                        result = WebHttp.error("Method Not Allowed");
                    } else {
                        result = identityService.dataLabel(body);
                    }
                }
                default -> {
                    status = 404;
                    result = WebHttp.error("Not Found");
                }
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
            host.log(exchange, status, start);
        }
    }

    private static Map<String, Object> inspectData(String raw) {
        try {
            return com.shenqianye.samsara.identity.IdentityDataInspector.inspect(
                    com.shenqianye.samsara.identity.IdentityService.getInstance().server(),
                    java.util.UUID.fromString(raw));
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "data UUID 格式不正确");
        }
    }

    private static Map<String, Object> backupData(Object raw) {
        try {
            return com.shenqianye.samsara.identity.IdentityDataInspector.backup(
                    java.util.UUID.fromString(String.valueOf(raw)));
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "data UUID 格式不正确");
        }
    }
}

