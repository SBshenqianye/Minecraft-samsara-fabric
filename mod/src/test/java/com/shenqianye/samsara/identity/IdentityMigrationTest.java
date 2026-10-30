package com.shenqianye.samsara.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.19 identity.json → v0.20 identity.yml 迁移。 */
class IdentityMigrationTest {
    @TempDir
    Path temp;

    @Test
    void migratesCanonicalAndFirstAliasesAndKeepsExtrasInReport() throws Exception {
        UUID canonical = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        Path old = temp.resolve("global/identity.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, """
                {
                  "identities": [
                    {
                      "canonicalUuid": "%s",
                      "name": "Bot2",
                      "aliases": [
                        {"uuid": "%s", "uuidType": "premium", "name": "Bot2"},
                        {"uuid": "%s", "uuidType": "offline", "name": "Bot2"},
                        {"uuid": "%s", "uuidType": "offline", "name": "旧号"}
                      ]
                    }
                  ]
                }
                """.formatted(canonical, premium, offline, extra), StandardCharsets.UTF_8);
        IdentityConfigStore store = new IdentityConfigStore(temp.resolve("identity.yml"));
        store.load();

        String message = IdentityMigration.migrateOld(temp.resolve("identity"), old, "", store);
        assertTrue(message.contains("迁移完成"), message);

        IdentityConfigStore reloaded = new IdentityConfigStore(store.file());
        IdentityConfig config = reloaded.load();
        assertTrue(reloaded.valid());
        assertEquals(1, config.groups.size());
        assertEquals(canonical, config.groups.get(0).dataUuid());
        assertEquals(premium, config.groups.get(0).premiumUuid());
        assertEquals(offline, config.groups.get(0).offlineUuid());
        assertFalse(config.enabled);

        Path archive = temp.resolve("identity/archive");
        assertTrue(Files.isDirectory(archive));
        try (var dirs = Files.list(archive)) {
            Path report = dirs.filter(Files::isDirectory)
                    .map(p -> p.resolve("migration-report.json"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElseThrow();
            String text = Files.readString(report);
            assertTrue(text.contains(extra.toString()));
        }
        assertTrue(Files.exists(temp.resolve("global/identity.json.v019.migrated")));
    }

    @Test
    void migrationIsIdempotentAfterGroupsExist() throws Exception {
        IdentityConfigStore store = new IdentityConfigStore(temp.resolve("identity.yml"));
        store.load();
        IdentityConfig config = new IdentityConfig();
        config.groups.add(new IdentityConfig.Group("A", UUID.randomUUID(), null, null, ""));
        assertTrue(store.save(config).ok());

        String message = IdentityMigration.migrateOld(temp.resolve("identity"),
                temp.resolve("no-such.json"), "", store);
        assertTrue(message.contains("已有身份组"));
    }
}
