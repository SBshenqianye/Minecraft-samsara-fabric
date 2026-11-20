package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.config.ConfigViews;
import com.shenqianye.samsara.config.spec.ConfigEditorService;
import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** `/api/config` 路由：PageSpec 页面、预览、存储估算与保存。 */
final class ConfigWebHandler {

    private static final Gson GSON = new GsonBuilder().create();

    private final LocalWebServer host;
    private final ConfigService service;
    private final ServerWebService serverService;

    ConfigWebHandler(LocalWebServer host, ConfigService service, ServerWebService serverService) {
        this.host = host;
        this.service = service;
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
            if ("GET".equals(method) && "/api/config/pages".equals(path)) {
                WebHttp.json(exchange, 200, ConfigEditorService.getInstance().pages(host.serverRoot()));
                return;
            }
            if ("POST".equals(method) && "/api/config/preview".equals(path)) {
                String body;
                try {
                    body = LocalWebServer.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                    return;
                }
                SaveRequest request = parseSave(body);
                if (request == null) {
                    status = 400;
                    WebHttp.json(exchange, status, WebHttp.error("请求体应为 {\"file\":\"samsara:commands\",\"values\":{...}}"));
                    return;
                }
                WebHttp.json(exchange, 200, ConfigEditorService.getInstance()
                        .preview(host.serverRoot(), request.file(), request.values()));
                return;
            }
            if ("GET".equals(method) && "/api/config/retention-estimate".equals(path)) {
                Map<String, String> query = WebHttp.parseQuery(exchange.getRequestURI().getRawQuery());
                String curveJson = query.get("curve");
                List<Map<String, Object>> raw;
                try {
                    raw = curveJson == null || curveJson.isBlank() ? List.of()
                            : GSON.fromJson(curveJson,
                            new TypeToken<List<Map<String, Object>>>() { }.getType());
                } catch (RuntimeException e) {
                    status = 400;
                    WebHttp.json(exchange, status, WebHttp.error("curve 应为 JSON 数组"));
                    return;
                }
                boolean keepAll = "1".equals(query.get("keepAll"))
                        || "true".equalsIgnoreCase(query.get("keepAll"));
                var usage = com.shenqianye.samsara.track.TrackRecorder.getInstance().usage();
                long actualPerSample = usage.totalSamples() > 0
                        ? Math.max(1L, Math.round(usage.bytesPerSample()))
                        : com.shenqianye.samsara.track.TrackRetention.BYTES_PER_SAMPLE;
                var estimate = com.shenqianye.samsara.track.TrackRetention.estimate(
                        com.shenqianye.samsara.track.TrackRetention.parseCurve(raw),
                        keepAll, actualPerSample);
                List<ConfigViews.CurveTier> tiers = new java.util.ArrayList<>();
                for (var tier : estimate.tiers()) {
                    tiers.add(new ConfigViews.CurveTier(tier.ageDays(), tier.keepRatio(),
                            tier.bytesPerDay(), tier.cumulativeBytes(), tier.older()));
                }
                WebHttp.json(exchange, 200, new ConfigViews.Curve(true,
                        estimate.bytesPerSample(), usage.bytesPerSample(),
                        usage.totalBytes(), usage.totalSamples(), usage.fileCount(),
                        usage.playerCount(), usage.bytesPerPlayer(),
                        estimate.freshSamplesPerDay(), estimate.freshBytesPerDay(),
                        estimate.steadyBytes(), estimate.growthPerDay(), estimate.unbounded(),
                        estimate.keepAll(), estimate.warning(), tiers));
                return;
            }
            if ("GET".equals(method)) {
                WebHttp.json(exchange, 200, service.configModel());
                return;
            }
            if (!"PUT".equals(method)) {
                status = 405;
                WebHttp.json(exchange, status, WebHttp.error("Method Not Allowed"));
                return;
            }
            String body;
            try {
                body = LocalWebServer.readBody(exchange);
            } catch (LocalWebServer.BodyTooLarge e) {
                status = 413;
                WebHttp.json(exchange, status, WebHttp.error("请求体过大"));
                return;
            }
            SaveRequest request = parseSave(body);
            if (request == null) {
                status = 400;
                WebHttp.json(exchange, status, WebHttp.error("请求体应为 {\"file\":\"commands\",\"values\":{...}}"));
                return;
            }
            if (request.file().contains(":") || !request.source().isBlank()) {
                ConfigViews.Save result = ConfigEditorService.getInstance().save(
                        serverService == null ? null : serverService.server(),
                        host.serverRoot(), request.file(), request.values(),
                        request.confirm(), request.revision());
                if (!result.ok()) {
                    status = 400;
                    WebHttp.json(exchange, status, result);
                    return;
                }
                WebHttp.json(exchange, 200, result);
                return;
            }
            ConfigService.SaveOutcome outcome = service.save(request.file(), request.values());
            if (!outcome.ok()) {
                status = 400;
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "配置校验失败");
                error.put("fields", outcome.errors());
                WebHttp.json(exchange, status, error);
                return;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("config", service.configModel());
            WebHttp.json(exchange, 200, result);
        } catch (RuntimeException e) {
            status = 500;
            WebHttp.json(exchange, status, WebHttp.error("服务器内部错误"));
            host.warnRoute(exchange, e);
        } finally {
            LocalWebServer.log(exchange, status, start);
        }
    }

    private static SaveRequest parseSave(String body) {
        try {
            JsonElement parsed = GSON.fromJson(body, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has("file") || !object.get("file").isJsonPrimitive()) {
                return null;
            }
            String file = object.get("file").getAsString();
            if (!object.has("values") || !object.get("values").isJsonObject()) {
                return null;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : object.getAsJsonObject("values").entrySet()) {
                values.put(e.getKey(), GSON.fromJson(e.getValue(), Object.class));
            }
            String source = object.has("source") && object.get("source").isJsonPrimitive()
                    ? object.get("source").getAsString() : "";
            String revision = object.has("revision") && object.get("revision").isJsonPrimitive()
                    ? object.get("revision").getAsString() : "";
            boolean confirm = object.has("confirm")
                    && object.get("confirm").isJsonPrimitive()
                    && object.get("confirm").getAsBoolean();
            return new SaveRequest(file, values, source, revision, confirm);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record SaveRequest(String file, Map<String, Object> values,
                               String source, String revision, boolean confirm) {
    }
}
