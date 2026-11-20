package com.shenqianye.samsara.web;

import com.shenqianye.samsara.lottery.LotteryViews;
import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/lottery` 路由：管理端模型、价值表、基金仓库、开奖/取消。 */
final class LotteryWebHandler {

    private final LocalWebServer host;
    private final LotteryWebService lotteryService;

    LotteryWebHandler(LocalWebServer host, LotteryWebService lotteryService) {
        this.host = host;
        this.lotteryService = lotteryService;
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
            if ("GET".equals(method) && "/api/lottery".equals(path)) {
                WebHttp.json(exchange, 200, lotteryService.adminModel());
                return;
            }
            if ("GET".equals(method) && "/api/lottery/values".equals(path)) {
                Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                WebHttp.json(exchange, 200, lotteryService.values(
                        query.getOrDefault("q", ""),
                        query.getOrDefault("category", ""),
                        query.getOrDefault("sort", "value-desc"),
                        WebHttp.intParam(query, "offset", 0),
                        WebHttp.intParam(query, "limit", 100)));
                return;
            }
            if ("GET".equals(method) && "/api/lottery/fund".equals(path)) {
                WebHttp.json(exchange, 200, lotteryService.fundInventory());
                return;
            }
            if ("POST".equals(method) && "/api/lottery/fund/write".equals(path)) {
                String body;
                try {
                    body = LocalWebServer.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                LotteryViews.Outcome result = lotteryService.writeFundInventory(body);
                status = result.httpStatus();
                WebHttp.json(exchange, status, result);
                return;
            }
            if ("POST".equals(method) && "/api/lottery/draw".equals(path)) {
                WebHttp.json(exchange, 200, lotteryService.draw());
                return;
            }
            if ("POST".equals(method) && "/api/lottery/cancel".equals(path)) {
                WebHttp.json(exchange, 200, lotteryService.cancel());
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, WebHttp.error("未知的乐透接口"));
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}

