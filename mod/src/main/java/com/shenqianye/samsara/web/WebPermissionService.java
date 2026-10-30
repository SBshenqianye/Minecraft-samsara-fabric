package com.shenqianye.samsara.web;

import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.permission.PermissionConfig;
import com.shenqianye.samsara.permission.PermissionTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生产环境权限树 Web 服务。 */
public final class WebPermissionService implements PermissionWebService {
    @Override
    public Map<String, Object> model() {
        PermissionConfig config = PermissionConfig.getInstance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", config.configPath());
        out.put("tree", config.tree().stream().map(WebPermissionService::node).toList());
        out.put("warnings", ConfigManager.getInstance().warnings());
        return out;
    }

    @Override
    public Map<String, Object> save(Map<String, Object> body) {
        Object raw = body.get("nodes");
        if (!(raw instanceof List<?> list)) {
            return fail("请求体应为 {\"nodes\":[...]}", Map.of("nodes", "应为数组"));
        }
        List<PermissionConfig.NodeChange> changes = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String field = "nodes[" + i + "]";
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.put(field, "节点应为对象");
                continue;
            }
            String path = map.get("path") == null ? "" : String.valueOf(map.get("path")).trim();
            if (path.isEmpty()) {
                errors.put(field + ".path", "权限路径不能为空");
                continue;
            }
            String level = map.containsKey("level") && map.get("level") != null
                    ? String.valueOf(map.get("level")) : null;
            Boolean disabled = null;
            if (map.containsKey("disabled") && map.get("disabled") != null) {
                Object value = map.get("disabled");
                if (value instanceof Boolean b) {
                    disabled = b;
                } else {
                    String text = String.valueOf(value).trim().toLowerCase();
                    if (text.equals("true") || text.equals("false")) {
                        disabled = Boolean.parseBoolean(text);
                    } else {
                        errors.put(field + ".disabled", "应为 true 或 false");
                    }
                }
            }
            changes.add(new PermissionConfig.NodeChange(path, level, disabled));
        }
        if (!errors.isEmpty()) {
            return fail("权限树请求格式不正确", errors);
        }
        Map<String, String> applied = PermissionConfig.getInstance().applyChanges(changes);
        if (!applied.isEmpty()) {
            return fail("权限树校验失败", applied);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "权限已保存并热重载");
        out.put("permissions", model());
        return out;
    }

    @Override
    public Map<String, Object> reload() {
        ConfigManager.getInstance().reload(ConfigSchema.PERMISSIONS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "权限配置已重载");
        out.put("permissions", model());
        return out;
    }

    private static Map<String, Object> node(PermissionTree.Node node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", node.path());
        out.put("label", node.label());
        out.put("level", node.level());
        out.put("effectiveLevel", node.effectiveLevel());
        out.put("disabled", node.disabled());
        out.put("disabledBy", node.disabledBy());
        out.put("source", node.source());
        List<Map<String, Object>> children = new ArrayList<>();
        for (PermissionTree.Node child : node.children()) {
            children.add(node(child));
        }
        out.put("children", children);
        return out;
    }

    private static Map<String, Object> fail(String error, Map<String, String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("fields", fields);
        return out;
    }
}
