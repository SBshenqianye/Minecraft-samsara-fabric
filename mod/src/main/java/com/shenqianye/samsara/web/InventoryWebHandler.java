package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/inventory` 路由（角色背包 / Web 背包 / 单物品操作）。 */
final class InventoryWebHandler {

    private final LocalWebServer host;
    private final InventoryWebService inventoryService;

    InventoryWebHandler(LocalWebServer host, InventoryWebService inventoryService) {
        this.host = host;
        this.inventoryService = inventoryService;
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
            if ("GET".equals(method) && "/api/inventory".equals(path)) {
                Map<String, Object> result = inventoryService.read(
                        WebHttp.parseQuery(exchange.getRequestURI().getRawQuery()));
                status = result.get("ok") == Boolean.TRUE ? 200
                        : result.get("status") instanceof Number number ? number.intValue() : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && ("/api/inventory/write".equals(path)
                    || "/api/inventory/item".equals(path))) {
                String body;
                try {
                    body = LocalWebServer.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                if ("/api/inventory/item".equals(path)) {
                    com.shenqianye.samsara.container.ContainerViews.Outcome outcome =
                            inventoryService.item(body);
                    status = outcome.httpStatus();
                    WebHttp.json(exchange, status, outcome);
                    return;
                }
                AgentAuthService.AuthContext context = AgentAuthService.getInstance()
                        .context(exchange);
                String actor = context == null ? "admin" : context.actor();
                Map<String, Object> result = inventoryService.write(body, actor);
                Object requested = result.get("status");
                status = requested instanceof Number number ? number.intValue()
                        : result.get("ok") == Boolean.TRUE ? 200 : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("GET".equals(method) && "/api/inventory/web".equals(path)) {
                WebHttp.json(exchange, 200, inventoryService.webRead());
                return;
            }
            if ("POST".equals(method) && ("/api/inventory/web/write".equals(path)
                    || "/api/inventory/web/deposit".equals(path)
                    || "/api/inventory/web/withdraw".equals(path))) {
                String body;
                try {
                    body = LocalWebServer.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                Map<String, Object> result = "/api/inventory/web/write".equals(path)
                        ? inventoryService.webWrite(body)
                        : "/api/inventory/web/deposit".equals(path)
                        ? inventoryService.webDeposit(body)
                        : inventoryService.webWithdraw(body);
                Object requested = result.get("status");
                status = requested instanceof Number number ? number.intValue()
                        : result.get("ok") == Boolean.TRUE ? 200 : 400;
                WebHttp.json(exchange, status, result);
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, WebHttp.error("未知背包接口"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error(WebHttp.errorDetail(e)));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
