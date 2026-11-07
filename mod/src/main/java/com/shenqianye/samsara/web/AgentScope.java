package com.shenqianye.samsara.web;

import java.util.List;
import java.util.Locale;

/** Agent token 权限范围。 */
public enum AgentScope {
    READ,
    ANNOUNCE,
    CONFIG,
    OPS,
    RCON,
    INVENTORY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AgentScope from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<String> ids() {
        return List.of("read", "announce", "config", "ops", "rcon", "inventory");
    }
}
