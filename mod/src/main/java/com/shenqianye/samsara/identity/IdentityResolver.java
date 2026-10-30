package com.shenqianye.samsara.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 身份解析纯函数：关闭开关/非法配置时原样返回自身 UUID。 */
public final class IdentityResolver {
    private IdentityResolver() {
    }

    public static IdentityConfig.Group groupOf(IdentityConfig config, UUID uuid) {
        if (uuid == null || config == null || config.groups == null) {
            return null;
        }
        for (IdentityConfig.Group group : config.groups) {
            if (group != null && (group.contains(uuid) || uuid.equals(group.dataUuid()))) {
                return group;
            }
        }
        return null;
    }

    public static IdentityConfig.Group groupByName(IdentityConfig config, String name) {
        if (name == null || config == null || config.groups == null) {
            return null;
        }
        String needle = name.trim().toLowerCase(Locale.ROOT);
        for (IdentityConfig.Group group : config.groups) {
            if (group != null && group.name != null
                    && group.name.toLowerCase(Locale.ROOT).equals(needle)) {
                return group;
            }
        }
        return null;
    }

    public static UUID identityOf(IdentityConfig config, boolean valid, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (config == null || !config.enabled || !valid) {
            return uuid;
        }
        IdentityConfig.Group group = groupOf(config, uuid);
        UUID data = group == null ? null : group.dataUuid();
        return data == null ? uuid : data;
    }

    public static List<UUID> aliasesOf(IdentityConfig config, UUID uuid) {
        IdentityConfig.Group group = groupOf(config, uuid);
        if (group == null) {
            return uuid == null ? List.of() : List.of(uuid);
        }
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        UUID data = group.dataUuid();
        if (data != null) {
            out.add(data);
        }
        out.addAll(group.accountUuids());
        if (out.isEmpty() && uuid != null) {
            out.add(uuid);
        }
        return List.copyOf(out);
    }

    public static List<UUID> dedupeByData(IdentityConfig config, boolean valid,
                                          Collection<UUID> uuids) {
        LinkedHashSet<UUID> seen = new LinkedHashSet<>();
        List<UUID> out = new ArrayList<>();
        if (uuids == null) {
            return out;
        }
        for (UUID uuid : uuids) {
            if (uuid == null) {
                continue;
            }
            if (seen.add(identityOf(config, valid, uuid))) {
                out.add(uuid);
            }
        }
        return out;
    }
}
