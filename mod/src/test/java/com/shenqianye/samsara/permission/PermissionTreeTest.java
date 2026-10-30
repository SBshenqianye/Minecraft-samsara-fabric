package com.shenqianye.samsara.permission;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限树：默认项无遗漏、祖先级联、叶子独立、树枝等级约束叶子。 */
class PermissionTreeTest {
    private static Map<String, PermissionLevel> defaults() {
        Map<String, PermissionLevel> map = new LinkedHashMap<>();
        PermissionDefaults.entries().forEach((path, level) ->
                map.put(path, PermissionLevel.fromString(level)));
        return map;
    }

    @Test
    void everyDefaultPermissionAppearsInTree() {
        Map<String, PermissionLevel> defaults = defaults();
        Map<String, PermissionLevel> entries = new LinkedHashMap<>(defaults);
        List<PermissionTree.Node> tree = PermissionTree.build(entries, Set.of(), defaults);

        Set<String> paths = new LinkedHashSet<>();
        collect(tree, paths);
        assertTrue(paths.contains("*"));
        for (String path : defaults.keySet()) {
            assertTrue(paths.contains(path), "权限树缺少默认项：" + path);
        }
        assertTrue(paths.contains("debug"), "必须补齐中间节点 debug");
    }

    @Test
    void ancestorLevelConstrainsLeaf() {
        Map<String, PermissionLevel> defaults = defaults();
        Map<String, PermissionLevel> entries = new LinkedHashMap<>(defaults);
        entries.put("season", PermissionLevel.OWNER);

        assertEquals(PermissionLevel.OWNER,
                PermissionTree.effectiveLevel("season status", entries, defaults));
        assertEquals(PermissionLevel.OWNER,
                PermissionTree.effectiveLevel("season create", entries, defaults));
    }

    @Test
    void ancestorDisabledCascadesToDescendants() {
        Set<String> disabled = Set.of("debug claim");
        assertEquals("debug claim", PermissionTree.disabledBy("debug claim give", disabled));
        assertEquals("debug claim", PermissionTree.disabledBy("debug claim", disabled));
        assertNull(PermissionTree.disabledBy("debug rewind", disabled));
        assertNull(PermissionTree.disabledBy("claim info", disabled));
    }

    @Test
    void rootDisabledClosesEverythingButBranchDoesNotAffectSibling() {
        Set<String> disabled = Set.of("*");
        assertTrue(PermissionTree.disabledBy("claim info", disabled).equals("*"));
        assertTrue(PermissionTree.disabledBy("debug rewind", disabled).equals("*"));

        Set<String> branch = Set.of("claim");
        assertNotNull(PermissionTree.disabledBy("claim info", branch));
        assertNull(PermissionTree.disabledBy("season status", branch));
    }

    @Test
    void subtreeAIsIndependentFromSubtreeB() {
        Map<String, PermissionLevel> defaults = defaults();
        Map<String, PermissionLevel> entries = new LinkedHashMap<>(defaults);
        entries.put("debug claim", PermissionLevel.CONFIG);
        assertEquals(PermissionLevel.CONFIG,
                PermissionTree.effectiveLevel("debug claim give", entries, defaults));
        assertEquals(PermissionLevel.OWNER,
                PermissionTree.effectiveLevel("debug rewind status", entries, defaults));
    }

    @Test
    void nodeMarksCustomSourceAndDisabledBy() {
        Map<String, PermissionLevel> defaults = defaults();
        Map<String, PermissionLevel> entries = new LinkedHashMap<>(defaults);
        entries.put("claim info", PermissionLevel.OP);
        List<PermissionTree.Node> tree = PermissionTree.build(entries, Set.of("claim"), defaults);
        PermissionTree.Node claim = find(tree, "claim");
        assertNotNull(claim);
        assertTrue(claim.disabled());
        PermissionTree.Node info = find(tree, "claim info");
        assertNotNull(info);
        assertEquals("claim", info.disabledBy());
        assertEquals("custom", info.source());
        assertEquals("op", info.level());
        assertFalse(info.disabled(), "子节点自身没有被关闭，只是被祖先级联");
    }

    @Test
    void extraCommandPathsFromServerTreeAreIncluded() {
        Map<String, PermissionLevel> defaults = defaults();
        Map<String, PermissionLevel> entries = new LinkedHashMap<>(defaults);
        List<PermissionTree.Node> tree = PermissionTree.build(entries, Set.of(), defaults,
                Set.of("season info", "track view", "where broadcast"));
        Set<String> paths = new LinkedHashSet<>();
        collect(tree, paths);
        assertTrue(paths.contains("season info"));
        assertTrue(paths.contains("track view"));
        assertTrue(paths.contains("where broadcast"));
    }

    @Test
    void commandPathNormalizesSamsaraRootAndAliases() {
        assertEquals("season create", PermissionCommandPaths.pathOf(
                List.of("samsara", "season", "create")));
        assertEquals("season create", PermissionCommandPaths.pathOf(
                List.of("season", "create")));
        assertEquals("", PermissionCommandPaths.pathOf(List.of("samsara")));
        assertEquals("mspt", PermissionCommandPaths.pathOf(List.of("mspt")));
    }

    private static void collect(List<PermissionTree.Node> nodes, Set<String> paths) {
        for (PermissionTree.Node node : nodes) {
            paths.add(node.path());
            collect(node.children(), paths);
        }
    }

    private static PermissionTree.Node find(List<PermissionTree.Node> nodes, String path) {
        for (PermissionTree.Node node : nodes) {
            if (node.path().equals(path)) {
                return node;
            }
            PermissionTree.Node child = find(node.children(), path);
            if (child != null) {
                return child;
            }
        }
        return null;
    }
}
