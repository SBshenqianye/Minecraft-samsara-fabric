package com.shenqianye.samsara.web;

import com.shenqianye.samsara.container.ContainerViews;
import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/containers` 路由：容器列表/读取、离线背包、扫描与写入。 */
final class ContainersWebHandler {

    private final LocalWebServer host;
    private final ContainerWebService containerService;

    ContainersWebHandler(LocalWebServer host, ContainerWebService containerService) {
        this.host = host;
        this.containerService = containerService;
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
            Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
            if ("GET".equals(method) && "/api/containers".equals(path)) {
                ContainerViews.Outcome result = containerService.list(query);
                WebHttp.json(exchange, 200, result);
                return;
            }
            if ("GET".equals(method) && "/api/containers/read".equals(path)) {
                ContainerViews.Outcome result = containerService.read(query);
                int code = result.ok() ? 200 : 404;
                status = code;
                WebHttp.json(exchange, code, result);
                return;
            }
            if ("GET".equals(method) && "/api/containers/offline".equals(path)) {
                ContainerViews.Outcome result = containerService.offline(query);
                status = result.ok() ? 200 : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && "/api/containers/scan".equals(path)) {
                String body;
                try {
                    body = host.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                ContainerViews.Outcome result = containerService.scan(body);
                status = result.httpStatus();
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && "/api/containers/item".equals(path)) {
                String body;
                try {
                    body = host.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                ContainerViews.Outcome result = containerService.item(body);
                status = result.httpStatus();
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && "/api/containers/write".equals(path)) {
                String body;
                try {
                    body = host.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                AgentAuthService.AuthContext context = AgentAuthService.getInstance()
                        .context(exchange);
                String actor = context == null ? "admin" : context.actor();
                ContainerViews.Outcome result = containerService.write(body, actor);
                status = result.httpStatus();
                WebHttp.json(exchange, status, result);
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, WebHttp.error("未知的容器接口"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error(WebHttp.errorDetail(e)));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
