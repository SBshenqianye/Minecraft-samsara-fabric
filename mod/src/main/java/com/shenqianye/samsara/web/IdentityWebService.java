package com.shenqianye.samsara.web;

import java.util.Map;

/** 独立身份组页面/API（不复用普通扁平配置表单）。 */
public interface IdentityWebService {
    Map<String, Object> model();

    /** 保存整份身份组配置；失败返回 {@code ok=false} 与字段级 errors。 */
    Map<String, Object> save(Map<String, Object> body);

    Map<String, Object> preview(Map<String, Object> body);

    /** data UUID 重绑：预览、二次确认、备份、复制/移动、更新 state、审计。 */
    Map<String, Object> rebind(Map<String, Object> body);

    Map<String, Object> reload();

    Map<String, Object> audit();

    Map<String, Object> generate();

    /** 名称/UUID 解析：名称 → 正版 + 离线 UUID；UUID → 名称（本地优先，必要时问 Mojang）。 */
    Map<String, Object> resolve(Map<String, Object> body);
}
