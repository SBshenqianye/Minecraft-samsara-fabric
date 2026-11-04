package com.shenqianye.samsara.lottery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 价值表锚点与推导（纯 ID，不启动原版注册表）。 */
class ItemValueServiceTest {
    private final ItemValueService values = ItemValueService.getInstance();

    @Test
    void anchorsAreStable() {
        assertEquals(1.0, values.unitValue("minecraft:iron_ingot"), 1e-6);
        assertEquals(4.0, values.unitValue("minecraft:gold_ingot"), 1e-6);
        assertEquals(32.0, values.unitValue("minecraft:diamond"), 1e-6);
        assertEquals(64.0, values.unitValue("minecraft:emerald"), 1e-6);
        assertEquals(128.0, values.unitValue("minecraft:netherite_ingot"), 1e-6);
    }

    @Test
    void reversibleBlocksStayConsistent() {
        assertEquals(9.0, values.unitValue("minecraft:iron_block"), 1e-6);
        assertEquals(288.0, values.unitValue("minecraft:diamond_block"), 1e-6);
    }

    @Test
    void toolsAreDerivedFromMaterial() {
        assertEquals(3.0 + 0.125, values.unitValue("minecraft:iron_pickaxe"), 1e-6);
        assertEquals(64.0 + 0.0625, values.unitValue("minecraft:diamond_sword"), 1e-6);
    }

    @Test
    void unknownItemsAreRejected() {
        assertEquals(0.0, values.unitValue(""), 1e-6);
        assertEquals(0.0, values.unitValue("minecraft:barrier"), 1e-6);
        assertTrue(values.unitValue("minecraft:iron_ingot") > 0.0);
    }
}
