package com.shenqianye.samsara.announce;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementStoreTest {
    @TempDir
    Path dir;

    @Test
    void saveAndLoadRoundTrip() {
        AnnouncementStore store = new AnnouncementStore(dir.resolve("announcements.json"));
        Announcement item = new Announcement();
        item.id = "test";
        item.name = "测试公告";
        item.trigger = "manual";
        item.chatText = "&ehello {player}";
        store.save(List.of(item));

        List<Announcement> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("test", loaded.get(0).id);
        assertEquals("&ehello {player}", loaded.get(0).chatText);
        assertTrue(store.file().toFile().exists());
    }

    @Test
    void validationRejectsBadFields() {
        Announcement item = new Announcement();
        item.id = "bad";
        item.trigger = "interval";
        item.intervalSeconds = 1;
        item.bossbarColor = "RAINBOW";
        item.target = "permission";
        item.targetValue = "";
        List<String> errors = AnnouncementStore.validateAll(List.of(item));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("间隔")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("bossbarColor")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("targetValue")));
    }

    @Test
    void scheduleRequiresValidTimeAndDuplicateIdRejected() {
        Announcement a = new Announcement();
        a.id = "same";
        a.trigger = "schedule";
        a.startAt = "not-a-time";
        Announcement b = new Announcement();
        b.id = "same";
        List<String> errors = AnnouncementStore.validateAll(List.of(a, b));
        assertTrue(errors.stream().anyMatch(e -> e.contains("startAt")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("id 重复")));
    }

    @Test
    void defaultsAreDisabledAndUsable() {
        List<Announcement> defaults = AnnouncementStore.defaults();
        assertEquals(1, defaults.size());
        assertFalse(defaults.get(0).enabled);
        assertTrue(AnnouncementStore.validateAll(defaults).isEmpty());
    }
}
