package com.shenqianye.samsara.container;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 容器视图 record 的 JSON 契约：字段名与旧 Map 输出一致，缺省字段不落键。 */
class ContainerViewsTest {

    private static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) {
        return GSON.fromJson(GSON.toJson(value), Map.class);
    }

    @Test
    void listEntryWithoutSlotsKeepsLegacyFields() {
        ContainerViews.Entry entry = new ContainerViews.Entry("minecraft:overworld", 1, 2, 3,
                "minecraft:chest", 27, 4, "", false, 7L, 100L, "cache", null);
        Map<String, Object> out = json(entry);
        assertEquals(Set.of("dimension", "x", "y", "z", "type", "size", "nonEmpty", "axis",
                "empty", "revision", "updatedAt", "source"), out.keySet());
        assertFalse(out.containsKey("slots"));
    }

    @Test
    void listPageKeepsHeaderFields() {
        ContainerViews.ListPage page = new ContainerViews.ListPage(true, "", 64, "", "",
                0, List.of(), 12);
        Map<String, Object> out = json(page);
        assertEquals(Set.of("ok", "dimension", "radius", "type", "q", "total", "containers",
                "indexSize"), out.keySet());
    }

    @Test
    void readCarriesContainerAndItems() {
        ContainerViews.Entry entry = new ContainerViews.Entry("d", 0, 0, 0, "t", 1, 0, "",
                true, 1L, 2L, "loaded", List.of(""));
        ContainerViews.Read read = new ContainerViews.Read(true, entry, List.of(Map.of("slot", 0)));
        Map<String, Object> out = json(read);
        assertEquals(Set.of("ok", "container", "items"), out.keySet());
        assertEquals(List.of("dimension", "x", "y", "z", "type", "size", "nonEmpty", "axis",
                "empty", "revision", "updatedAt", "source", "slots"),
                List.copyOf(((Map<String, Object>) out.get("container")).keySet()));
    }

    @Test
    void scanBusyKeepsRetryAndStatus() {
        Map<String, Object> out = json(new ContainerViews.ScanBusy(false, "slow", 900L, 429));
        assertEquals(Set.of("ok", "error", "retryAfterMs", "status"), out.keySet());
    }

    @Test
    void writeOmitsNullOptionalFields() {
        Map<String, Object> out = json(new ContainerViews.Write(true, false, null, null, 200,
                null, null));
        assertEquals(Set.of("ok", "queued", "status"), out.keySet());
    }

    @Test
    void statusErrorKeepsStatus() {
        Map<String, Object> out = json(new ContainerViews.StatusError(false, 400, "bad"));
        assertEquals(Set.of("ok", "status", "error"), out.keySet());
    }
}
