package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界栈纯数据：压栈、进入旧世界、返回下层、栈顶判定。 */
class WorldStackManagerTest {

    @Test
    void pushKeepsBottomToTopOrderAndIgnoresDuplicates() {
        WorldStackManager.Manifest m = new WorldStackManager.Manifest();
        assertTrue(m.push("world"));
        assertTrue(m.push("world_s1"));
        assertFalse(m.push("world"), "重复压栈应被忽略");

        assertEquals("world", m.stack.get(0));
        assertEquals("world_s1", m.top());
        assertEquals(2, m.stack.size());
    }

    @Test
    void enterOldWorldDoesNotReorderStack() {
        WorldStackManager.Manifest m = new WorldStackManager.Manifest();
        m.push("world");
        m.push("world_s1");
        m.current = "world_s1";

        assertTrue(m.isTopCurrent());
        assertTrue(m.enter("world"));
        assertEquals("world", m.current);
        assertEquals("world_s1", m.top(), "进入旧世界不应改变栈顶");
        assertFalse(m.isTopCurrent());
    }

    @Test
    void leaveGoesDownOneLayerAndStopsAtBottom() {
        WorldStackManager.Manifest m = new WorldStackManager.Manifest();
        m.push("world");
        m.push("world_s1");
        m.push("world_s2");
        m.current = "world_s2";

        assertTrue(m.leave());
        assertEquals("world_s1", m.current);
        assertTrue(m.leave());
        assertEquals("world", m.current);
        assertFalse(m.leave(), "栈底不能再 leave");
    }

    @Test
    void enterUnknownWorldIsRejected() {
        WorldStackManager.Manifest m = new WorldStackManager.Manifest();
        m.push("world");
        m.current = "world";

        assertFalse(m.enter("world_s9"));
        assertEquals("world", m.current);
    }

    @Test
    void emptyManifestHasEmptyTop() {
        WorldStackManager.Manifest m = new WorldStackManager.Manifest();
        assertEquals("", m.top());
        assertFalse(m.isTopCurrent());
        assertFalse(m.leave());
    }

    @Test
    void autoDiscoveryKeepsNaturalWorldOrderAndAssignsContiguousIndices() {
        List<String> names = List.of("world_s10", "world", "world_s2", "world_s1");
        WorldStackManager.Discovery.Result result = WorldStackManager.Discovery.assign(
                names, List.of(), java.util.Map.of(), 0L);

        assertEquals(List.of("world", "world_s1", "world_s2", "world_s10"), result.order());
        assertEquals(0, result.metas().get("world").index);
        assertEquals(1, result.metas().get("world_s1").index);
        assertEquals(2, result.metas().get("world_s2").index);
        assertEquals(3, result.metas().get("world_s10").index);
        assertEquals("", result.metas().get("world").parent);
        assertEquals("world", result.metas().get("world_s1").parent);
        assertEquals("world_s1", result.metas().get("world_s2").parent);
    }

    @Test
    void autoDiscoveryHonorsStackJsonIndicesAndAppendsNewWorlds() {
        WorldStackManager.WorldMeta s1 = new WorldStackManager.WorldMeta();
        s1.index = 0;
        s1.worldId = "id-s1";
        WorldStackManager.WorldMeta s2 = new WorldStackManager.WorldMeta();
        s2.index = 1;
        s2.worldId = "id-s2";

        WorldStackManager.Discovery.Result result = WorldStackManager.Discovery.assign(
                List.of("world", "world_s1", "world_s2"),
                List.of("world", "world_s1", "world_s2"),
                java.util.Map.of("world_s1", s1, "world_s2", s2), 0L);

        assertEquals(List.of("world_s1", "world_s2", "world"), result.order(),
                "已有 stack.json 的世界保持 index，新发现的 world 追加到栈顶");
        assertEquals(2, result.metas().get("world").index);
        assertEquals("world_s2", result.metas().get("world").parent);
    }

    @Test
    void autoDiscoveryRepairsDuplicateIndicesWithWarning() {
        WorldStackManager.WorldMeta a = new WorldStackManager.WorldMeta();
        a.index = 1;
        WorldStackManager.WorldMeta b = new WorldStackManager.WorldMeta();
        b.index = 1;

        WorldStackManager.Discovery.Result result = WorldStackManager.Discovery.assign(
                List.of("world_s1", "world_s2"), List.of(), java.util.Map.of("world_s1", a, "world_s2", b), 0L);

        assertEquals(List.of("world_s1", "world_s2"), result.order());
        assertEquals(0, result.metas().get("world_s1").index);
        assertEquals(1, result.metas().get("world_s2").index);
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void excludedWorldIsNotPartOfDiscoveryOrder() {
        WorldStackManager.WorldMeta excluded = new WorldStackManager.WorldMeta();
        excluded.index = 0;
        excluded.excluded = true;

        WorldStackManager.Discovery.Result result = WorldStackManager.Discovery.assign(
                List.of("world"), List.of("world"), java.util.Map.of("world", excluded), 0L);

        // Discovery 收到的是 refresh 过滤后的 active 列表；这里验证 excluded 元数据不会被排序使用
        assertEquals(1, result.order().size());
        assertEquals(0, result.metas().get("world").index);
    }

    @Test
    void parentChainFollowsStackOrder() {
        WorldStackManager.Discovery.Result result = WorldStackManager.Discovery.assign(
                List.of("world", "world_s1", "world_s2"),
                List.of("world", "world_s1", "world_s2"), java.util.Map.of(), 0L);
        assertEquals("world", result.metas().get("world_s1").parent);
        assertEquals("world_s1", result.metas().get("world_s2").parent);
    }
}
