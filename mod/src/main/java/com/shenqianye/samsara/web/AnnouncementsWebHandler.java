package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.announce.Announcement;
import com.shenqianye.samsara.announce.AnnouncementService;
import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** `/api/announcements` 路由：列表、保存、预览、发送、停止。 */
final class AnnouncementsWebHandler {

    private static final Gson GSON = new GsonBuilder().create();

    private final LocalWebServer host;
    private final ServerWebService serverService;

    AnnouncementsWebHandler(LocalWebServer host, ServerWebService serverService) {
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
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            AnnouncementService service = AnnouncementService.getInstance();
            if ("GET".equals(method)) {
                WebHttp.json(exchange, 200, service.model());
                return;
            }
            String body;
            try {
                body = host.readBody(exchange);
            } catch (LocalWebServer.BodyTooLarge e) {
                status = 413;
                WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                return;
            }
            JsonObject object;
            try {
                JsonElement parsed = GSON.fromJson(body, JsonElement.class);
                object = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                object = null;
            }
            if (object == null) {
                status = 400;
                WebHttp.json(exchange, status, WebHttp.error("请求体应为 JSON 对象"));
                return;
            }
            if ("PUT".equals(method)) {
                List<Announcement> list = null;
                if (object.has("announcements") && object.get("announcements").isJsonArray()) {
                    list = GSON.fromJson(object.get("announcements"),
                            new TypeToken<List<Announcement>>() { }.getType());
                }
                if (list == null) {
                    status = 400;
                    WebHttp.json(exchange, status, WebHttp.error("缺少 announcements 数组"));
                    return;
                }
                Map<String, Object> result = service.save(list);
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    status = 400;
                }
                WebHttp.json(exchange, status, result);
                return;
            }
            if (!"POST".equals(method)) {
                status = 405;
                WebHttp.json(exchange, status, WebHttp.error("Method Not Allowed"));
                return;
            }
            String player = object.has("player") && object.get("player").isJsonPrimitive()
                    ? object.get("player").getAsString() : "";
            Announcement announcement = null;
            if (object.has("announcement") && object.get("announcement").isJsonObject()) {
                announcement = GSON.fromJson(object.get("announcement"), Announcement.class);
            } else if (object.has("id") && object.get("id").isJsonPrimitive()) {
                String id = object.get("id").getAsString();
                announcement = service.announcements().stream()
                        .filter(item -> id.equals(item.id)).findFirst().orElse(null);
            }
            if ("/api/announcements/preview".equals(path)) {
                WebHttp.json(exchange, 200, service.preview(serverService == null ? null
                        : serverService.server(), announcement, player));
                return;
            }
            if ("/api/announcements/stop".equals(path)) {
                String id = object.has("id") && object.get("id").isJsonPrimitive()
                        ? object.get("id").getAsString() : "";
                WebHttp.json(exchange, 200, service.stop(id));
                return;
            }
            if ("/api/announcements/send".equals(path)) {
                boolean dryRun = !object.has("dryRun")
                        || !object.get("dryRun").isJsonPrimitive()
                        || object.get("dryRun").getAsBoolean();
                boolean confirm = object.has("confirm")
                        && object.get("confirm").isJsonPrimitive()
                        && object.get("confirm").getAsBoolean();
                Map<String, Object> result = service.send(serverService == null ? null
                                : serverService.server(), announcement, player,
                        dryRun, confirm, "web");
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    status = 400;
                }
                WebHttp.json(exchange, status, result);
                return;
            }
            status = 404;
            WebHttp.json(exchange, status, WebHttp.error("Not Found"));
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error("服务器内部错误"));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }
}
