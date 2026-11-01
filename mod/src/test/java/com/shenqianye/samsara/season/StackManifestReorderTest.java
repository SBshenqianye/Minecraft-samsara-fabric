package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 栈位排序：只改 index/parent，id 和目录名保持稳定。 */
class StackManifestReorderTest {
    @TempDir
    Path temp;

    @Test
    void reorderKeepsIdsAndSurvivesReload() throws Exception {
        StackManifest manifest = new StackManifest();
        manifest.layers.add(layer(0, "stack_0", false));
        manifest.layers.add(layer(1, "stack_1", false));
        manifest.layers.add(layer(2, "stack_2", false));
        manifest.layers.add(layer(3, "world", true));
        manifest.normalize();
        manifest.save(temp);

        manifest.reorderBelowTop(List.of("stack_2", "stack_0", "stack_1"));
        manifest.save(temp);
        StackManifest reloaded = StackManifest.load(temp);

        assertEquals(List.of("stack_2", "stack_0", "stack_1", "world"),
                reloaded.layers.stream().map(l -> l.id).toList());
        assertEquals(0, reloaded.byId("stack_2").index);
        assertEquals(1, reloaded.byId("stack_0").index);
        assertEquals(2, reloaded.byId("stack_1").index);
        assertEquals("stack_2", reloaded.byId("stack_0").parent);
        assertEquals("stack_0", reloaded.byId("stack_1").parent);
        assertEquals("stack_1", reloaded.top().parent);
        assertTrue(reloaded.top().top);
        assertEquals(StackPaths.TOP_ID, reloaded.top().id);
    }

    @Test
    void normalizeDoesNotRenameExistingLayersAfterReorder() throws Exception {
        StackManifest manifest = new StackManifest();
        manifest.layers.add(layer(0, "stack_0", false));
        manifest.layers.add(layer(1, "stack_1", false));
        manifest.layers.add(layer(2, "world", true));
        manifest.reorderBelowTop(List.of("stack_1", "stack_0"));
        manifest.save(temp);

        StackManifest reloaded = StackManifest.load(temp);
        assertNotNull(reloaded.byId("stack_1"));
        assertNotNull(reloaded.byId("stack_0"));
        assertFalse(reloaded.byId("stack_1").top);
        assertEquals(0, reloaded.byId("stack_1").index);
        assertEquals("stack_1", reloaded.byId("stack_0").parent);
    }

    private static StackLayer layer(int index, String id, boolean top) {
        StackLayer l = new StackLayer();
        l.index = index;
        l.id = id;
        l.top = top;
        l.seed = index + 1;
        l.worldId = "w" + index;
        return l;
    }
}
