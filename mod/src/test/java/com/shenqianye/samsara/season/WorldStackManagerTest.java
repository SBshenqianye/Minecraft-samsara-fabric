package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

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
}
