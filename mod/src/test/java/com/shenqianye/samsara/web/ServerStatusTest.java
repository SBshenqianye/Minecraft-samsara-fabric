package com.shenqianye.samsara.web;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** `/api/status` 的类型化响应必须保持旧字段名。 */
class ServerStatusTest {

    @Test
    void jsonKeepsLegacyFieldNames() {
        ServerStatus status = new ServerStatus(
                "0.41.2", "0.62.9+test", "26.3", "/srv/config", "/srv/world/samsara",
                "/srv/samsara/global", true, "已迁移", "成功",
                "/srv/world/samsara/identity", true, "/srv/config/identity.yml",
                List.of("warning"), List.of(new ServerStatus.ConfigFileRef("commands", "/srv/config/commands.yml")));
        String json = new Gson().toJson(status);
        for (String field : List.of("version", "webVersion", "minecraft", "configRoot",
                "worldDataRoot", "globalDataRoot", "globalFollowWorld", "migration",
                "globalMigration", "identityRoot", "identityEnabled", "identityConfig",
                "warnings", "configFiles")) {
            assertTrue(json.contains("\"" + field + "\""), field + " missing: " + json);
        }
        assertTrue(json.contains("\"id\":\"commands\""), json);
        assertTrue(json.contains("\"path\":\"/srv/config/commands.yml\""), json);
    }
}
