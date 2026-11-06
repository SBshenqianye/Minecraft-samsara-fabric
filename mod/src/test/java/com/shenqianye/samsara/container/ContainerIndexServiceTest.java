package com.shenqianye.samsara.container;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexServiceTest {
    @Test
    void entryMatchesOnlyExactEncodedItems() {
        ContainerEntry entry = new ContainerEntry();
        entry.dimension = "minecraft:overworld";
        entry.x = 1;
        entry.y = 2;
        entry.z = 3;
        entry.type = "minecraft:chest";
        entry.size = 27;
        entry.nonEmpty = 2;
        entry.slots = new ArrayList<>(List.of("diamond", "", "emerald"));
        assertTrue(entry.matches("diamond"));
        assertTrue(entry.matches("emerald"));
        assertFalse(entry.matches("gold"));
        assertFalse(entry.matches(""));
        assertFalse(entry.empty());
    }

    @Test
    void shardRoundTripKeepsSlotsAndRevision() {
        ContainerEntry entry = new ContainerEntry();
        entry.dimension = "samsara:stack_0/overworld";
        entry.x = 512;
        entry.y = 64;
        entry.z = -513;
        entry.type = "minecraft:barrel";
        entry.size = 27;
        entry.nonEmpty = 1;
        entry.revision = 7;
        entry.updatedAt = 123456789L;
        entry.slots = new ArrayList<>(List.of("encoded-nbt"));
        ContainerShard shard = new ContainerShard();
        shard.dimension = entry.dimension;
        shard.regionX = entry.x >> 9;
        shard.regionZ = entry.z >> 9;
        shard.entries = new ArrayList<>(List.of(entry));

        String json = new Gson().toJson(shard);
        ContainerShard parsed = new Gson().fromJson(json, ContainerShard.class);
        assertEquals(1, parsed.entries.size());
        ContainerEntry restored = parsed.entries.get(0);
        assertEquals(entry.dimension, restored.dimension);
        assertEquals(entry.x, restored.x);
        assertEquals(entry.y, restored.y);
        assertEquals(entry.z, restored.z);
        assertEquals(entry.type, restored.type);
        assertEquals(entry.revision, restored.revision);
        assertEquals(entry.updatedAt, restored.updatedAt);
        assertEquals(List.of("encoded-nbt"), restored.slots);
        assertTrue(restored.matches("encoded-nbt"));
    }

    @Test
    void contentComparisonDrivesRevision() {
        ContainerEntry entry = new ContainerEntry();
        entry.type = "minecraft:chest";
        entry.slots = new ArrayList<>(List.of("a", ""));
        assertTrue(entry.sameContent("minecraft:chest", List.of("a", "")));
        assertFalse(entry.sameContent("minecraft:barrel", List.of("a", "")));
        assertFalse(entry.sameContent("minecraft:chest", List.of("b", "")));
    }

    @Test
    void indexQueryRespectsDimensionFilter() {
        ContainerIndexService service = ContainerIndexService.getInstance();
        service.clearForTest();
        ContainerEntry overworld = entry("minecraft:overworld", 1, 2, 3, "diamond");
        ContainerEntry nether = entry("minecraft:the_nether", 4, 5, 6, "diamond");
        service.putForTest(overworld);
        service.putForTest(nether);

        assertTrue(service.containsEncoded("diamond", false, "minecraft:overworld"));
        assertFalse(service.containsEncoded("diamond", false, "minecraft:the_end"));
        assertTrue(service.containsEncoded("diamond", true, "minecraft:the_end"));
        assertFalse(service.containsEncoded("gold", true, "minecraft:overworld"));
        service.clearForTest();
    }

    @Test
    void indexListFiltersByRadiusAndType() {
        ContainerIndexService service = ContainerIndexService.getInstance();
        service.clearForTest();
        service.putForTest(entry("minecraft:overworld", 0, 64, 0, "a"));
        service.putForTest(entry("minecraft:overworld", 200, 64, 0, "b"));
        var model = service.list("minecraft:overworld", 0, 0, 32, "minecraft:chest");
        assertEquals(1, model.get("total"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) model.get("containers");
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).get("x"));
        service.clearForTest();
    }

    private static ContainerEntry entry(String dimension, int x, int y, int z, String item) {
        ContainerEntry entry = new ContainerEntry();
        entry.dimension = dimension;
        entry.x = x;
        entry.y = y;
        entry.z = z;
        entry.type = "minecraft:chest";
        entry.size = 27;
        entry.nonEmpty = 1;
        entry.slots = new ArrayList<>(List.of(item));
        return entry;
    }
}
