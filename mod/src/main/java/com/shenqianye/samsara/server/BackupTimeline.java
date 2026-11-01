package com.shenqianye.samsara.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用备份时间线：把带 {@code parentId} 的备份节点组织成可分支的树。
 *
 * <p>本体备份（{@link ServerBackup}）与后续的世界栈备份共用这套模型：
 * 每次创建备份时记录父节点；恢复某个旧节点后，下一次备份会从该节点分出新分支。
 * 没有 parentId 的旧数据按创建时间串成一条主线，便于平滑迁移。
 */
public final class BackupTimeline {
    private BackupTimeline() {
    }

    public static final class Entry {
        public String id = "";
        public String parentId = "";
        public String branchId = "";
        public String label = "";
        public long createdAt;
        public final Map<String, Object> data = new LinkedHashMap<>();
    }

    public static final class Tree {
        public String headId = "";
        public final List<Map<String, Object>> nodes = new ArrayList<>();
    }

    public static Tree build(List<Entry> entries, String headId) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt));
        Map<String, Entry> byId = new HashMap<>();
        for (Entry entry : ordered) {
            if (entry.id != null && !entry.id.isBlank()) {
                byId.put(entry.id, entry);
            }
        }
        Map<String, String> parent = new HashMap<>();
        Map<String, List<Entry>> children = new LinkedHashMap<>();
        String previous = "";
        for (Entry entry : ordered) {
            String resolved = entry.parentId;
            if (resolved == null || resolved.isBlank() || !byId.containsKey(resolved)
                    || resolved.equals(entry.id)) {
                resolved = previous;
            }
            parent.put(entry.id, resolved == null ? "" : resolved);
            if (!resolved.isEmpty()) {
                children.computeIfAbsent(resolved, key -> new ArrayList<>()).add(entry);
            }
            previous = entry.id;
        }

        String currentId = headId == null ? "" : headId;
        if (!byId.containsKey(currentId)) {
            currentId = ordered.isEmpty() ? "" : ordered.get(ordered.size() - 1).id;
        }

        Tree tree = new Tree();
        tree.headId = currentId;
        Set<String> roots = new HashSet<>();
        for (Entry entry : ordered) {
            String resolved = parent.get(entry.id);
            if (resolved == null || resolved.isEmpty()) {
                roots.add(entry.id);
            }
        }
        Set<String> visited = new HashSet<>();
        for (Entry root : ordered) {
            if (roots.contains(root.id)) {
                append(tree, root, 0, "", currentId, parent, children, byId, visited);
            }
        }
        for (Entry entry : ordered) {
            if (!visited.contains(entry.id)) {
                append(tree, entry, 0, "", currentId, parent, children, byId, visited);
            }
        }
        return tree;
    }

    private static void append(Tree tree, Entry entry, int depth, String parentBranch,
                               String currentId, Map<String, String> parent,
                               Map<String, List<Entry>> children, Map<String, Entry> byId,
                               Set<String> visited) {
        if (entry == null || entry.id == null || !visited.add(entry.id)) {
            return;
        }
        String resolvedParent = parent.getOrDefault(entry.id, "");
        String branch = entry.branchId;
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
        int nodeDepth = depth + (parentBranch.isEmpty() || parentBranch.equals(branch) ? 0 : 1);
        boolean hasChildren = children.containsKey(entry.id) && !children.get(entry.id).isEmpty();

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", entry.id);
        node.put("parentId", resolvedParent);
        node.put("branchId", branch);
        node.put("label", entry.label == null ? "" : entry.label);
        node.put("createdAt", entry.createdAt);
        node.put("depth", nodeDepth);
        node.put("current", entry.id.equals(currentId));
        node.put("restorable", true);
        node.put("deletable", !entry.id.equals(currentId) && !hasChildren);
        node.put("hasChildren", hasChildren);
        node.putAll(entry.data);
        tree.nodes.add(node);

        List<Entry> next = children.get(entry.id);
        if (next == null) {
            return;
        }
        next.sort(Comparator.comparingLong((Entry child) -> child.createdAt));
        for (Entry child : next) {
            append(tree, child, nodeDepth, branch, currentId, parent, children, byId, visited);
        }
    }

    /**
     * 保留最新 N 份，同时保留这些备份的祖先和当前时间线节点，避免出现悬空父节点。
     */
    public static Set<String> retentionKeep(List<Entry> entries, int keep, String headId) {
        Set<String> kept = new HashSet<>();
        if (keep <= 0) {
            return kept;
        }
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
        Map<String, String> parent = new HashMap<>();
        for (Entry entry : ordered) {
            parent.put(entry.id, entry.parentId == null ? "" : entry.parentId);
        }
        int count = 0;
        for (Entry entry : ordered) {
            if (count >= keep) {
                break;
            }
            kept.add(entry.id);
            count++;
        }
        if (headId != null && !headId.isBlank()) {
            kept.add(headId);
        }
        // 祖先闭包
        for (String id : new ArrayList<>(kept)) {
            String cursor = parent.get(id);
            int guard = 0;
            while (cursor != null && !cursor.isEmpty() && kept.add(cursor) && guard++ < 100_000) {
                cursor = parent.get(cursor);
            }
        }
        return kept;
    }
}
