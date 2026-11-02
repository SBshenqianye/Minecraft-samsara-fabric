package com.shenqianye.samsara.web;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 地图总览：玩家维度 → 栈层/三界短名的映射。 */
class WebMapServicePlayerTest {

    @Test
    void topStackDimensionsMapToWorld() {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse("minecraft:overworld"));
        Map<String, Object> entry = WebMapService.playerEntry(
                "Alice", "uuid-a", key, 12.34, 64.0, -5.6, 90.0f, 20.0f);
        assertNotNull(entry);
        assertEquals("world", entry.get("stackId"));
        assertEquals("overworld", entry.get("dimension"));
        assertEquals("minecraft:overworld", entry.get("dimensionId"));
        assertEquals(12.3, ((Number) entry.get("x")).doubleValue(), 1e-6);
        assertEquals(-5.6, ((Number) entry.get("z")).doubleValue(), 1e-6);
    }

    @Test
    void oldStackDimensionsMapToStackId() {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("samsara", "stack_2/the_nether"));
        Map<String, Object> entry = WebMapService.playerEntry(
                "Bob", "uuid-b", key, 10.0, 70.0, 20.0, 0.0f, 18.0f);
        assertNotNull(entry);
        assertEquals("stack_2", entry.get("stackId"));
        assertEquals("the_nether", entry.get("dimension"));
        assertEquals("samsara:stack_2/the_nether", entry.get("dimensionId"));
    }

    @Test
    void unmanagedDimensionsAreIgnored() {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("other", "moon"));
        assertNull(WebMapService.playerEntry(
                "Carol", "uuid-c", key, 0.0, 0.0, 0.0, 0.0f, 20.0f));
    }
}
