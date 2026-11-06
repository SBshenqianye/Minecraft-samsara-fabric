package com.shenqianye.samsara.container;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerWriteResultTest {
    @Test
    void successfulWriteReturnsEntryAnd200() {
        ContainerEntry entry = new ContainerEntry();
        entry.dimension = "minecraft:overworld";
        entry.x = 8;
        entry.y = 100;
        entry.z = 8;
        entry.type = "minecraft:chest";
        entry.size = 27;
        entry.nonEmpty = 1;
        entry.revision = 4;
        entry.slots = new ArrayList<>(List.of("encoded"));
        ContainerIndexService.WriteResult result = ContainerIndexService.WriteResult.ok(entry);
        assertTrue(result.ok());
        assertEquals(200, result.status());
        assertNotNull(result.toModel().get("container"));
    }

    @Test
    void revisionConflictReturns409AndCurrentEntry() {
        ContainerEntry entry = new ContainerEntry();
        entry.dimension = "minecraft:overworld";
        entry.x = 8;
        entry.y = 100;
        entry.z = 8;
        entry.revision = 9;
        ContainerIndexService.WriteResult result = ContainerIndexService.WriteResult
                .conflict(entry, "revision 9");
        assertFalse(result.ok());
        assertEquals(409, result.status());
        assertEquals("revision 9", result.error());
        assertEquals(9, result.entry().revision);
    }

    @Test
    void missingContainerReturns400WithoutEntry() {
        ContainerIndexService.WriteResult result = ContainerIndexService.WriteResult
                .error(400, "该坐标没有可编辑的容器");
        assertFalse(result.ok());
        assertEquals(400, result.status());
        assertEquals(null, result.entry());
    }
}
