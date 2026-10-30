package com.shenqianye.samsara.permission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置权限默认值（纯数据，不读盘）。
 *
 * <p>这里是权限默认值的<b>唯一来源</b>：{@code ConfigSchema} 用它生成默认 YAML，
 * {@link PermissionConfig} 用它兜底；不要在别处再抄一份。
 */
public final class PermissionDefaults {
    private PermissionDefaults() {
    }

    public static Map<String, String> entries() {
        Map<String, String> map = new LinkedHashMap<>();
        // season
        map.put("season create", "owner");
        map.put("season start", "owner");
        map.put("season end", "owner");
        map.put("season skip", "owner");
        map.put("season status", "user");
        map.put("season respawn-groups", "owner");


        // where
        map.put("where", "user");

        // tpa
        map.put("tpa", "user");
        map.put("tpahere", "user");
        map.put("tpaccept", "user");
        map.put("tpdeny", "user");
        map.put("tpcancel", "user");

        // claim
        map.put("claim give", "op");
        map.put("claim list", "user");
        map.put("claim info", "user");
        map.put("claim scan", "op");
        map.put("claim rebuild", "op");
        map.put("claim preview", "op");
        map.put("claim remove", "op");
        map.put("claim export", "op");

        // debug（全部 owner）
        map.put("debug flow", "owner");
        map.put("debug diff", "owner");
        map.put("debug rewind", "owner");
        map.put("debug claim", "owner");
        map.put("debug delete", "owner");
        map.put("debug config", "owner");
        map.put("debug fakeplayers", "owner");
        map.put("debug groups", "owner");
        map.put("debug create", "owner");
        map.put("debug perm", "owner");
        map.put("debug settime", "owner");
        map.put("debug reload", "owner");
        map.put("debug reset", "owner");
        map.put("debug spawninfo", "owner");
        map.put("debug track", "owner");
        map.put("debug completion", "owner");

        // perm
        map.put("perm list", "user");
        map.put("perm set", "owner");
        map.put("perm reset", "owner");
        map.put("perm info", "user");
        map.put("perm owner", "config");
        return map;
    }
}