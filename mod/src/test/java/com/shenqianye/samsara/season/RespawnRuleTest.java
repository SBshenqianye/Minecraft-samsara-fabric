package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 老栈层重生回退规则。 */
class RespawnRuleTest {

    @Test
    void sameDimensionKeepsPlayerSpawn() {
        assertFalse(RespawnRule.needsFallback("minecraft:overworld", "minecraft:overworld"));
        assertFalse(RespawnRule.needsFallback(
                "ResourceKey[minecraft:dimension / samsara:stack_0]",
                "ResourceKey[minecraft:dimension / samsara:stack_0]"));
    }

    @Test
    void fallbackToTopWorldIsCorrected() {
        // 老世界死亡、原版回退到顶层：必须纠正。
        assertTrue(RespawnRule.needsFallback(
                "ResourceKey[minecraft:dimension / samsara:stack_0]",
                "ResourceKey[minecraft:dimension / minecraft:overworld]"));
    }

    @Test
    void missingRespawnPointFallsBackToDeathLayer() {
        assertTrue(RespawnRule.needsFallback("ResourceKey[minecraft:dimension / samsara:stack_1]", null));
        assertTrue(RespawnRule.needsFallback("ResourceKey[minecraft:dimension / samsara:stack_1]", ""));
    }

    @Test
    void unknownDeathDimensionDoesNothing() {
        assertFalse(RespawnRule.needsFallback(null, "minecraft:overworld"));
        assertFalse(RespawnRule.needsFallback("", "minecraft:overworld"));
    }
}
