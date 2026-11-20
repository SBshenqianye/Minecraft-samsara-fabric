package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/map` 路由：地图模型、玩家、传送门、实时层与瓦片。 */
final class MapWebHandler {

    private final LocalWebServer host;
    private final WebMapService mapService;

    MapWebHandler(LocalWebServer host, WebMapService mapService) {
        this.host = host;
        this.mapService = mapService;
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
            if (mapService == null) {
                status = 503;
                WebHttp.json(exchange, status, Map.of("error", "地图服务未启动"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                WebHttp.json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
            if ("/api/map".equals(path)) {
                WebHttp.json(exchange, 200, mapService.model());
                return;
            }
            if ("/api/map/players".equals(path)) {
                WebHttp.json(exchange, 200, mapService.players());
                return;
            }
            if ("/api/map/portals".equals(path)) {
                WebHttp.json(exchange, 200, mapService.portals());
                return;
            }
            if ("/api/map/live".equals(path)) {
                WebHttp.json(exchange, 200, mapService.live());
                return;
            }
            if ("/api/map/player-trails".equals(path)) {
                Integer limit = WebHttp.intParam(query.get("limit"));
                int maxPoints = limit == null ? 400 : Math.max(2, Math.min(2000, limit));
                WebHttp.json(exchange, 200, mapService.playerTrails(maxPoints));
                return;
            }
            if ("/api/map/tile".equals(path)) {
                String stack = query.getOrDefault("stack", "");
                String dimension = query.getOrDefault("dim", "");
                Integer rx = WebHttp.intParam(query.get("rx"));
                Integer rz = WebHttp.intParam(query.get("rz"));
                Integer scale = WebHttp.intParam(query.get("scale"));
                if (dimension.isBlank() || rx == null || rz == null || scale == null) {
                    status = 400;
                    WebHttp.json(exchange, status, Map.of("error",
                            "参数应为 stack=<栈层>&dim=<维度>&rx=<int>&rz=<int>&scale=1|2|4|8"));
                    return;
                }
                if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
                    status = 400;
                    WebHttp.json(exchange, status, Map.of("error", "scale 只能是 1、2、4、8"));
                    return;
                }
                byte[] png = mapService.tile(stack, dimension, rx, rz, scale,
                        !"0".equals(query.get("shade")));
                if (png == null) {
                    status = 404;
                    WebHttp.json(exchange, status, Map.of("error", "没有这个地图区块"));
                    return;
                }
                WebHttp.sendBytes(exchange, 200, "image/png", png);
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
