package com.shenqianye.samsara.web;

import java.util.Map;

/** 输出格式 Web 服务接口（测试可替换）。 */
public interface OutputWebService {
    /** GET /api/output */
    Map<String, Object> model();

    /** PUT /api/output */
    Map<String, Object> save(Map<String, Object> body);

    /** POST /api/output/preview */
    Map<String, Object> preview(Map<String, Object> body);

    /** POST /api/output/reload */
    Map<String, Object> reload();
}
