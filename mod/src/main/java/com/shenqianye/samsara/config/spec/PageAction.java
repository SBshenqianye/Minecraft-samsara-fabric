package com.shenqianye.samsara.config.spec;

/** PageSpec 声明的动作；前端按 action.id 绑定，不硬编码每个页面。 */
public record PageAction(String id, String label, String method, String path,
                         boolean requiresConfirmation) {
    public PageAction {
        id = id == null ? "" : id;
        label = label == null ? id : label;
        method = method == null ? "POST" : method;
        path = path == null ? "" : path;
    }
}
