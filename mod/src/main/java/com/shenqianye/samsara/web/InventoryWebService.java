package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shenqianye.samsara.container.PlayerInventoryService;
import com.shenqianye.samsara.container.WebInventoryService;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Type;
import java.util.Map;

/** 玩家背包 Web API 适配层；核心读写与安全队列在 {@link PlayerInventoryService}。 */
public final class InventoryWebService {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private final WebInventoryService webInventory;

    public InventoryWebService() {
        this(null);
    }

    public InventoryWebService(MinecraftServer server) {
        this.webInventory = new WebInventoryService(server);
    }

    public Map<String, Object> read(Map<String, String> query) {
        return PlayerInventoryService.getInstance().read(query);
    }

    public Map<String, Object> write(String body, String actor) {
        return PlayerInventoryService.getInstance().write(body, actor);
    }

    public Map<String, Object> item(String body) {
        return PlayerInventoryService.getInstance().item(body);
    }

    public Map<String, Object> webRead() {
        return webInventory.read();
    }

    public Map<String, Object> webWrite(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return webInventory.write(strings(request.get("slots")));
    }

    public Map<String, Object> webDeposit(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        return webInventory.deposit(string(request.get("encoded")));
    }

    public Map<String, Object> webWithdraw(String body) {
        Map<String, Object> request = parse(body);
        if (request == null) return error(400, "请求体应为 JSON 对象");
        int index = intValue(request.get("index"), -1);
        int count = intValue(request.get("count"), 0);
        return webInventory.withdraw(index, count);
    }

    private static Map<String, Object> parse(String body) {
        try {
            Map<String, Object> parsed = GSON.fromJson(body, MAP_TYPE);
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static java.util.List<String> strings(Object raw) {
        if (!(raw instanceof java.util.List<?> list)) return null;
        java.util.List<String> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) out.add(item == null ? "" : String.valueOf(item));
        return out;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> error(int status, String message) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", status);
        out.put("error", message);
        return out;
    }
}
