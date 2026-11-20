package com.shenqianye.samsara.web;

import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** `/api/world-stack` 路由：栈层、导入/归档、图标与时间线。 */
final class WorldStackWebHandler {

    private final LocalWebServer host;
    private final WorldStackWebService worldStackService;

    WorldStackWebHandler(LocalWebServer host, WorldStackWebService worldStackService) {
        this.host = host;
        this.worldStackService = worldStackService;
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
            if (worldStackService == null) {
                status = 503;
                WebHttp.json(exchange, status, Map.of("error", "世界栈服务未启动"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            int bodyLimit = "/api/world-stack/icon".equals(path)
                    ? WebSecurity.MAX_ICON_BODY_BYTES : WebSecurity.MAX_BODY_BYTES;
            Map<String, Object> body = Map.of();
            if ("POST".equals(method) || "PUT".equals(method)) {
                try {
                    body = host.parseObject(LocalWebServer.readBody(exchange, bodyLimit));
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
            Object result;
            if ("GET".equals(method)) {
                if ("/api/world-stack".equals(path)) {
                    result = worldStackService.model();
                } else if ("/api/world-stack/timeline".equals(path)) {
                    result = worldStackService.timeline();
                } else if ("/api/world-stack/timeline/export".equals(path)) {
                    Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                    result = worldStackService.exportTimeline(query.get("id"));
                } else if ("/api/world-stack/timeline/snapshots".equals(path)) {
                    result = worldStackService.timelineSnapshots();
                } else if ("/api/world-stack/jobs".equals(path)) {
                    result = worldStackService.jobs();
                } else if (path.startsWith("/api/world-stack/jobs/")) {
                    String id = URLDecoder.decode(
                            path.substring("/api/world-stack/jobs/".length()),
                            StandardCharsets.UTF_8);
                    result = worldStackService.job(id);
                } else {
                    status = 404;
                    result = Map.of("error", "Not Found");
                }
            } else if ("POST".equals(method)) {
                switch (path) {
                    case "/api/world-stack/import/preview" ->
                            result = worldStackService.previewImport(body);
                    case "/api/world-stack/import/cancel" ->
                            result = worldStackService.cancelImport(body);
                    case "/api/world-stack/import" ->
                            result = worldStackService.importWorld(body);
                    case "/api/world-stack/reorder" ->
                            result = worldStackService.reorder(body);
                    case "/api/world-stack/archive/preview" ->
                            result = worldStackService.previewArchive(body);
                    case "/api/world-stack/archive" ->
                            result = worldStackService.archive(body);
                    case "/api/world-stack/icon" ->
                            result = worldStackService.saveIcon(body);
                    case "/api/world-stack/icon/delete" ->
                            result = worldStackService.deleteIcon(body);
                    case "/api/world-stack/timeline/preview" ->
                            result = worldStackService.previewTimelineRestore(body);
                    case "/api/world-stack/timeline/restore" ->
                            result = worldStackService.restoreTimeline(body);
                    case "/api/world-stack/timeline/cleanup" ->
                            result = worldStackService.cleanupTimelineSnapshot(body);
                    default -> {
                        status = 404;
                        result = Map.of("error", "Not Found");
                    }
                }
            } else {
                status = 405;
                result = Map.of("error", "Method Not Allowed");
            }
            if (status == 200 && result instanceof Map<?, ?> map
                    && Boolean.FALSE.equals(map.get("ok"))) {
                status = 400;
            }
            WebHttp.json(exchange, status, result);
        } catch (RuntimeException | LinkageError e) {
            status = 500;
            WebHttp.json(exchange, status, Map.of("error", "服务器内部错误：" + WebHttp.errorDetail(e)));
            com.shenqianye.samsara.Samsara.LOGGER.warn("[web] 处理 {} 失败: {}", exchange.getRequestURI().getPath(), e.toString());
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}

