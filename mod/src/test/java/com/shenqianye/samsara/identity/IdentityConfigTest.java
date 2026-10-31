package com.shenqianye.samsara.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** identity.yml 生成/往返/校验与 identityOf 解析。 */
class IdentityConfigTest {
    @TempDir
    Path temp;

    @Test
    void defaultFileIsGeneratedWithIdentityDisabled() throws Exception {
        IdentityConfigStore store = new IdentityConfigStore(temp.resolve("identity.yml"));
        IdentityConfig config = store.load();
        assertFalse(config.enabled);
        assertTrue(config.syncPlayerData);
        assertEquals(32, config.maxOwners);
        assertTrue(store.valid());
        assertTrue(Files.exists(store.file()));
        String text = Files.readString(store.file());
        assertTrue(text.contains("identity:"));
        assertTrue(text.contains("syncPlayerData: true"));
        assertTrue(text.contains("groups: []"));
    }

    @Test
    void saveAndReloadRoundTrip() {
        IdentityConfigStore store = new IdentityConfigStore(temp.resolve("identity.yml"));
        store.load();
        UUID data = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.enabled = true;
        config.maxOwners = 8;
        config.groups.add(new IdentityConfig.Group("Steve", data, premium, offline, "主号 + 小号"));
        IdentityConfigStore.SaveResult saved = store.save(config);
        assertTrue(saved.ok(), () -> String.valueOf(saved.errors()));
        assertTrue(Files.exists(store.file().resolveSibling("identity.yml.bak")));

        IdentityConfigStore reloaded = new IdentityConfigStore(store.file());
        IdentityConfig loaded = reloaded.load();
        assertTrue(reloaded.valid());
        assertTrue(loaded.enabled);
        assertEquals(8, loaded.maxOwners);
        assertEquals(1, loaded.groups.size());
        assertEquals(data, loaded.groups.get(0).dataUuid());
        assertEquals(premium, loaded.groups.get(0).premiumUuid());
        assertEquals(offline, loaded.groups.get(0).offlineUuid());
    }

    @Test
    void duplicateUuidAcrossGroupsIsRejected() {
        UUID shared = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.groups.add(new IdentityConfig.Group("A", UUID.randomUUID(), shared, null, ""));
        config.groups.add(new IdentityConfig.Group("B", UUID.randomUUID(), null, shared, ""));
        List<IdentityValidator.Issue> issues = IdentityValidator.validate(config);
        assertFalse(issues.isEmpty());
    }

    @Test
    void dataMayEqualOwnAccountButNotAnotherGroupsAccount() {
        UUID data = UUID.randomUUID();
        IdentityConfig own = new IdentityConfig();
        own.groups.add(new IdentityConfig.Group("A", data, data, null, ""));
        assertTrue(IdentityValidator.validate(own).isEmpty());

        IdentityConfig cross = new IdentityConfig();
        cross.groups.add(new IdentityConfig.Group("A", data, null, null, ""));
        cross.groups.add(new IdentityConfig.Group("B", UUID.randomUUID(), data, null, ""));
        assertFalse(IdentityValidator.validate(cross).isEmpty());
    }

    @Test
    void dataUuidMayBeSharedByMultipleGroups() {
        UUID shared = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.groups.add(new IdentityConfig.Group("A", shared, null, null, ""));
        config.groups.add(new IdentityConfig.Group("B", shared, null, null, ""));
        assertTrue(IdentityValidator.validate(config).isEmpty());
    }

    @Test
    void dataMayBeSharedWhenItIsTheOtherGroupsOwnAccount() {
        UUID self = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.groups.add(new IdentityConfig.Group("A", self, self, null, ""));
        config.groups.add(new IdentityConfig.Group("B", self, null, null, ""));
        assertTrue(IdentityValidator.validate(config).isEmpty());
    }

    @Test
    void groupNamesAreUniqueIgnoringCase() {
        IdentityConfig config = new IdentityConfig();
        config.groups.add(new IdentityConfig.Group("Steve", UUID.randomUUID(), null, null, ""));
        config.groups.add(new IdentityConfig.Group("steve", UUID.randomUUID(), null, null, ""));
        assertFalse(IdentityValidator.validate(config).isEmpty());
    }

    @Test
    void invalidValuesAreReportedWithoutWriting() {
        IdentityConfigStore store = new IdentityConfigStore(temp.resolve("identity.yml"));
        store.load();
        IdentityConfig config = new IdentityConfig();
        config.maxOwners = 0;
        IdentityConfig.Group group = new IdentityConfig.Group("A", null, null, null, "");
        group.data = "not-a-uuid";
        config.groups.add(group);
        IdentityConfigStore.SaveResult result = store.save(config);
        assertFalse(result.ok());
        assertTrue(result.errors().containsKey("pets.maxOwners"));
        assertTrue(result.errors().containsKey("groups[0].data"));
    }

    @Test
    void identityOfResolvesAliasesAndHonoursSwitch() {
        UUID data = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.enabled = true;
        config.groups.add(new IdentityConfig.Group("A", data, premium, offline, ""));

        assertEquals(data, IdentityResolver.identityOf(config, true, premium));
        assertEquals(data, IdentityResolver.identityOf(config, true, offline));
        assertEquals(data, IdentityResolver.identityOf(config, true, data));
        UUID stranger = UUID.randomUUID();
        assertEquals(stranger, IdentityResolver.identityOf(config, true, stranger));

        config.enabled = false;
        assertEquals(premium, IdentityResolver.identityOf(config, true, premium));
        assertEquals(premium, IdentityResolver.identityOf(config, false, premium));
    }

    @Test
    void aliasesAndDedupeUseDataUuid() {
        UUID data = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        IdentityConfig config = new IdentityConfig();
        config.enabled = true;
        config.groups.add(new IdentityConfig.Group("A", data, premium, offline, ""));
        assertTrue(IdentityResolver.aliasesOf(config, premium).contains(data));
        assertTrue(IdentityResolver.aliasesOf(config, premium).contains(premium));
        assertTrue(IdentityResolver.aliasesOf(config, premium).contains(offline));
        List<UUID> deduped = IdentityResolver.dedupeByData(config, true,
                List.of(premium, offline, premium));
        assertEquals(List.of(premium), deduped);
    }
}
