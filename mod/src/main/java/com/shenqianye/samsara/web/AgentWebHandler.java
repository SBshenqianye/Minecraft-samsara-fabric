package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.web.http.WebHttp;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** `/api/agent` 路由：whoami 与 Agent token 管理（仅管理员）。 */
final class AgentWebHandler {

    private static final Gson GSON = new GsonBuilder().create();

    private final LocalWebServer host;

    AgentWebHandler(LocalWebServer host) {
        this.host = host;
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
            AgentAuthService.AuthContext context = AgentAuthService.getInstance()
                    .context(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/api/agent/whoami".equals(path) && "GET".equals(method)) {
                WebHttp.json(exchange, 200, AgentAuthService.getInstance().describe(context));
                return;
            }
            if ("/api/agent/tokens".equals(path)) {
                AgentTokenStore store = AgentTokenStore.getInstance();
                if ("GET".equals(method)) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("path", store.file().toString());
                    out.put("tokens", store.listPublic());
                    out.put("scopes", AgentScope.ids());
                    WebHttp.json(exchange, 200, out);
                    return;
                }
                String body;
                try {
                    body = host.readBody(exchange);
                } catch (LocalWebServer.BodyTooLarge e) {
                    status = 413;
                    WebHttp.json(exchange, status, Map.of("error", "请求体过大"));
                    return;
                }
                Map<String, Object> request = GSON.fromJson(body,
                        new TypeToken<Map<String, Object>>() { }.getType());
                if (request == null) {
                    status = 400;
                    WebHttp.json(exchange, status, Map.of("error", "请求体应为 JSON 对象"));
                    return;
                }
                if ("POST".equals(method)) {
                    Object rawScopes = request.get("scopes");
                    List<String> scopes = rawScopes instanceof List<?> list
                            ? list.stream().map(String::valueOf).toList() : List.of();
                    Map<String, Object> result = store.create(
                            String.valueOf(request.getOrDefault("name", "")), scopes);
                    if (!Boolean.TRUE.equals(result.get("ok"))) {
                        status = 400;
                    }
                    WebHttp.json(exchange, status, result);
                    return;
                }
                if ("DELETE".equals(method)) {
                    Map<String, Object> result = store.revoke(
                            String.valueOf(request.getOrDefault("name", "")));
                    if (!Boolean.TRUE.equals(result.get("ok"))) {
                        status = 400;
                    }
                    WebHttp.json(exchange, status, result);
                    return;
                }
                status = 405;
                WebHttp.json(exchange, status, Map.of("error", "Method Not Allowed"));
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
