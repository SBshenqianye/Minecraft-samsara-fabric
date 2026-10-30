package com.shenqianye.samsara.web;

import java.util.Map;

/** 权限树 Web 服务接口（测试可替换）。 */
public interface PermissionWebService {
    /** GET /api/permissions */
    Map<String, Object> model();

    /** PUT /api/permissions */
    Map<String, Object> save(Map<String, Object> body);

    /** POST /api/permissions/reload */
    Map<String, Object> reload();
}
