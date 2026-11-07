package com.shenqianye.samsara.web;

import com.shenqianye.samsara.container.PlayerInventoryService;

import java.util.Map;

/** 玩家背包 Web API 适配层；核心读写与安全队列在 {@link PlayerInventoryService}。 */
public final class InventoryWebService {
    public Map<String, Object> read(Map<String, String> query) {
        return PlayerInventoryService.getInstance().read(query);
    }

    public Map<String, Object> write(String body, String actor) {
        return PlayerInventoryService.getInstance().write(body, actor);
    }

    public Map<String, Object> item(String body) {
        return PlayerInventoryService.getInstance().item(body);
    }
}
