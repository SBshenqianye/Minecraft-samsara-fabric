package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/items` 路由：物品 schema、describe/build/raw/craft。 */
final class ItemsWebHandler {

    private final LocalWebServer host;
    private final ItemWebService itemService;

    ItemsWebHandler(LocalWebServer host, ItemWebService itemService) {
        this.host = host;
        this.itemService = itemService;
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
            if ("GET".equals(method) && "/api/items/schema".equals(path)) {
                Map<String, Object> result = itemService.schema();
                status = result.get("ok") == Boolean.TRUE ? 200 : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && ("/api/items/describe".equals(path)
                    || "/api/items/build".equals(path) || "/api/items/raw".equals(path)
                    || "/api/items/craft".equals(path))) {
                String body;
                try {
                    body = host.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                Map<String, Object> result = "/api/items/describe".equals(path)
                        ? itemService.describe(body) : "/api/items/raw".equals(path)
                        ? itemService.raw(body) : "/api/items/craft".equals(path)
                        ? itemService.craft(body) : itemService.build(body);
                Object requested = result.get("status");
                status = requested instanceof Number number ? number.intValue()
                        : result.get("ok") == Boolean.TRUE ? 200 : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, WebHttp.error("未知物品接口"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error(WebHttp.errorDetail(e)));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
