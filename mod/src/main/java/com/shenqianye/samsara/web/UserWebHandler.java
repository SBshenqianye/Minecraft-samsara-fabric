package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/** `/api/user` 公开只读接口：状态、世界栈、乐透、图标、皮肤与地图。 */
final class UserWebHandler {

    private final WebUserService userService;

    UserWebHandler(WebUserService userService) {
        this.userService = userService;
    }

    void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            if (userService == null) {
                status = 503;
                WebHttp.json(exchange, status, Map.of("error", "用户端 Web 未启动"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405;
                WebHttp.json(exchange, status, Map.of("error", "Method Not Allowed"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
            if ("/api/user/status".equals(path)) {
                WebHttp.json(exchange, 200, userService.status());
                return;
            }
            if ("/api/user/worlds".equals(path)) {
                WebHttp.json(exchange, 200, userService.worlds());
                return;
            }
            if ("/api/user/lottery".equals(path)) {
                WebHttp.json(exchange, 200, userService.lottery());
                return;
            }
            if ("/api/user/lottery/values".equals(path)) {
                WebHttp.json(exchange, 200, userService.lotteryValues(
                        query.getOrDefault("q", ""),
                        query.getOrDefault("category", ""),
                        query.getOrDefault("sort", "value-desc"),
                        WebHttp.intParam(query, "offset", 0),
                        WebHttp.intParam(query, "limit", 100)));
                return;
            }
            if ("/api/user/worlds/icon".equals(path)) {
                WorldStackIconService.IconFile icon = userService.icon(query.getOrDefault("stack", ""));
                if (icon == null) {
                    status = 404;
                    WebHttp.json(exchange, status, Map.of("error", "没有这个栈层图标"));
                    return;
                }
                WebHttp.sendBytes(exchange, 200, icon.mime(), icon.bytes());
                return;
            }
            if ("/api/user/map/avatar".equals(path)) {
                String id = query.getOrDefault("id", "");
                String uuid = query.getOrDefault("uuid", id);
                String name = query.getOrDefault("name", id);
                MojangSkinService.Skin skin = userService.avatar(uuid, name);
                if (skin == null) {
                    status = 404;
                    WebHttp.json(exchange, status, Map.of("error", "没有可用的 Mojang 皮肤"));
                    return;
                }
                WebHttp.sendBytes(exchange, 200, skin.mime(), skin.bytes());
                return;
            }
            if ("/api/user/map/head".equals(path)) {
                String id = query.getOrDefault("id", "");
                String uuid = query.getOrDefault("uuid", id);
                String name = query.getOrDefault("name", id);
                MojangSkinService.Skin skin = userService.head(uuid, name);
                if (skin == null) {
                    status = 404;
                    WebHttp.json(exchange, status, Map.of("error", "没有可用的玩家头像"));
                    return;
                }
                WebHttp.sendBytes(exchange, 200, skin.mime(), skin.bytes());
                return;
            }
            if ("/api/user/map/body".equals(path)) {
                String id = query.getOrDefault("id", "");
                MojangSkinService.Skin skin = userService.body(
                        query.getOrDefault("uuid", id), query.getOrDefault("name", id));
                if (skin == null) {
                    status = 404;
                    WebHttp.json(exchange, status, Map.of("error", "没有可用的 3D 皮肤"));
                    return;
                }
                WebHttp.sendBytes(exchange, 200, skin.mime(), skin.bytes());
                return;
            }
            if ("/api/user/map".equals(path)) {
                WebHttp.json(exchange, 200, userService.mapModel());
                return;
            }
            if ("/api/user/map/players".equals(path)) {
                WebHttp.json(exchange, 200, userService.players());
                return;
            }
            if ("/api/user/map/live".equals(path)) {
                WebHttp.json(exchange, 200, userService.live());
                return;
            }
            if ("/api/user/map/tile".equals(path)) {
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
                byte[] png = userService.tile(stack, dimension, rx, rz, scale,
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
            SamsaraLog.warnUser(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }

    /** 用户端错误日志统一入口，避免 handler 依赖 LocalWebServer 的 LOG。 */
    private static final class SamsaraLog {
        private static void warnUser(HttpExchange exchange, RuntimeException e) {
            com.shenqianye.samsara.Samsara.LOGGER.warn("[web] 用户端处理 {} 失败: {}",
                    exchange.getRequestURI().getPath(), e.getMessage());
        }
    }
}

