package com.shenqianye.samsara.season;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新栈清单：world 是栈顶，数字越大越新。 */
class WorldStackManagerTest {

    @Test
    void topIsWorldAndOldLayersAreBelow() {
        StackManifest m = new StackManifest();
        StackLayer a = new StackLayer();
        a.id = "stack_0";
        a.index = 0;
        a.seed = 1;
        StackLayer b = new StackLayer();
        b.id = "stack_1";
        b.index = 1;
        b.seed = 2;
        StackLayer top = new StackLayer();
        top.id = "world";
        top.top = true;
        top.seed = 3;

        m.layers.add(a);
        m.layers.add(b);
        m.layers.add(top);
        m.normalize();

        assertEquals(3, m.layers.size());
        assertEquals("world", m.top().id);
        assertEquals(2, m.top().index);
        assertEquals("stack_1", m.below(m.top()).id);
        assertEquals("stack_0", m.below(m.below(m.top())).id);
        assertTrue(m.top().top);
        assertFalse(m.layers.get(0).top);
    }

    @Test
    void ensureTopMovesExistingWorldToStack() {
        StackManifest m = new StackManifest();
        StackLayer old = new StackLayer();
        old.id = "world";
        old.top = true;
        old.seed = 1;
        m.layers.add(old);
        m.normalize();

        StackLayer newTop = new StackLayer();
        newTop.id = "world";
        newTop.top = true;
        newTop.seed = 2;
        old.top = false;
        old.id = "stack_0";
        old.index = 0;
        m.ensureTop(newTop);

        assertEquals("world", m.top().id);
        assertEquals(1, m.top().index);
        assertEquals(2, m.layers.size());
        assertEquals("stack_0", m.layers.get(0).id);
    }

    @Test
    void pathsDistinguishTopAndStackDimensions() {
        ResourceKey<Level> key = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "samsara", "stack_2/overworld"));
        assertEquals("stack_2", StackPaths.layerIdOf(key));
        assertEquals("overworld", StackPaths.dimensionOf(key));
        assertEquals(StackPaths.TOP_ID, "world");
    }

    @Test
    void manifestIdsAndParentsAreNormalized() {
        StackManifest m = new StackManifest();
        StackLayer top = new StackLayer();
        top.id = "world";
        top.top = true;
        m.layers.add(top);
        m.normalize();
        assertEquals("", m.top().parent);
        assertEquals(0, m.top().index);
    }

    @Test
    void dataRootAndDimensionRootFollowLayer() {
        java.nio.file.Path world = java.nio.file.Path.of("world");
        java.nio.file.Path topRoot = StackPaths.dataRootFor(world, StackPaths.TOP_ID);
        java.nio.file.Path stackRoot = StackPaths.dataRootFor(world, "stack_1");
        assertEquals(world.resolve("samsara"), topRoot);
        assertEquals(world.resolve("samsara/stack/stack_1"), stackRoot);
        assertEquals(world.resolve("dimensions/minecraft/overworld"),
                StackPaths.layerDimensionFor(world, "world", "overworld"));
        assertEquals(world.resolve("dimensions/samsara/stack_1/overworld"),
                StackPaths.layerDimensionFor(world, "stack_1", "overworld"));

        ResourceKey<Level> key = StackPaths.keyFromString("samsara:stack_1/overworld");
        assertEquals("stack_1", StackPaths.layerIdOf(key));
        assertEquals("overworld", StackPaths.dimensionOf(key));
        assertEquals(stackRoot, StackPaths.dataRootFor(world, key));
    }

    @Test
    void rewindDataDefaultsPairLayerAndDimension() {
        com.shenqianye.samsara.rewind.RewindData old = new com.shenqianye.samsara.rewind.RewindData();
        old.worldKey = "stack_0";
        old.normalize();
        assertEquals("stack_0", old.layerId);
        assertEquals("minecraft:overworld", old.dimension);

        com.shenqianye.samsara.rewind.RewindData legacy = new com.shenqianye.samsara.rewind.RewindData();
        legacy.normalize();
        assertEquals(StackPaths.TOP_ID, legacy.layerId);
    }
}
