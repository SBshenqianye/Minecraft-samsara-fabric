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

/** 账号切换文件同步、journal 回滚与 state.json。 */
class IdentityDataSyncTest {
    @TempDir
    Path temp;

    private IdentityDataSync sync() {
        return new IdentityDataSync(temp.resolve("identity"),
                temp.resolve("world/playerdata"),
                temp.resolve("world/advancements"),
                temp.resolve("world/stats"));
    }

    @Test
    void pushAndPullCopyPlayerDataMetadata() throws Exception {
        IdentityDataSync sync = sync();
        UUID current = UUID.randomUUID();
        UUID data = UUID.randomUUID();
        Files.createDirectories(sync.worldData(current).getParent());
        Files.createDirectories(sync.worldAdvancements(current).getParent());
        Files.createDirectories(sync.worldStats(current).getParent());
        Files.writeString(sync.worldData(current), "player", StandardCharsets.UTF_8);
        Files.writeString(sync.worldAdvancements(current), "adv", StandardCharsets.UTF_8);
        Files.writeString(sync.worldStats(current), "stats", StandardCharsets.UTF_8);

        assertTrue(sync.push(current, data, "A").ok());
        assertEquals("player", Files.readString(sync.identityData(data)));
        assertEquals("adv", Files.readString(sync.identityAdvancements(data)));
        assertEquals("stats", Files.readString(sync.identityStats(data)));

        Files.writeString(sync.worldData(current), "changed", StandardCharsets.UTF_8);
        assertTrue(sync.pull(data, current, "A").ok());
        assertEquals("player", Files.readString(sync.worldData(current)));
        assertTrue(Files.exists(sync.worldData(current).resolveSibling(
                sync.worldData(current).getFileName() + ".bak")));
    }

    @Test
    void pullWithoutIdentityDataIsBusinessFailureAndDoesNotOverwrite() throws Exception {
        IdentityDataSync sync = sync();
        UUID current = UUID.randomUUID();
        Files.createDirectories(sync.worldData(current).getParent());
        Files.writeString(sync.worldData(current), "keep", StandardCharsets.UTF_8);
        IdentityDataSync.Result result = sync.pull(UUID.randomUUID(), current, "A");
        assertFalse(result.ok());
        assertTrue(result.businessFailure());
        assertEquals("keep", Files.readString(sync.worldData(current)));
    }

    @Test
    void preparedJournalRollsBackAndCommittedJournalIsCleaned() throws Exception {
        IdentityDataSync sync = sync();
        UUID data = UUID.randomUUID();
        Path target = sync.identityData(data);
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Files.writeString(backup, "old", StandardCharsets.UTF_8);
        Path journal = sync.journal().prepare("A", data, data, target, backup,
                IdentityJournal.sha256(target));
        Files.writeString(target, "broken", StandardCharsets.UTF_8);

        IdentityJournal.Recovery recovery = sync.journal().recover();
        assertEquals(1, recovery.rolledBack());
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(journal));

        Path committed = sync.journal().prepare("A", data, data, target, backup,
                IdentityJournal.sha256(target));
        sync.journal().commit(committed);
        assertFalse(Files.exists(committed));
    }

    @Test
    void stateStoreRoundTripsLastWriterAndBlockedFlag() {
        IdentityStateStore store = new IdentityStateStore(temp.resolve("state.json"));
        store.load();
        UUID data = UUID.randomUUID();
        UUID writer = UUID.randomUUID();
        IdentityStateStore.GroupState state = store.getOrCreate(data);
        state.lastWriter = writer.toString();
        state.lastSyncAt = 123L;
        state.syncBlocked = true;
        store.put(data, state);
        store.save();

        IdentityStateStore reloaded = new IdentityStateStore(store.file());
        reloaded.load();
        IdentityStateStore.GroupState loaded = reloaded.get(data);
        assertEquals(writer, loaded.lastWriterUuid());
        assertEquals(123L, loaded.lastSyncAt);
        assertTrue(loaded.syncBlocked);
    }
}
