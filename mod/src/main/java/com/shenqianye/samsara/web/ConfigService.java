package com.shenqianye.samsara.web;

import java.util.Map;

/**
 * Web 面板的配置服务接口。
 *
 * <p>生产实现读 {@code ConfigManager}；测试用假实现，从而可以在纯 JUnit 里启动
 * 真正的 {@code HttpServer} 覆盖鉴权/校验/保存/重载/端口占用。
 */
public interface ConfigService {
    /** GET /api/config */
    Map<String, Object> configModel();

    /** PUT /api/config；失败返回字段级错误。 */
    SaveOutcome save(String fileId, Map<String, Object> values);

    /** POST /api/reload */
    Map<String, Object> reload();

    /** GET /api/status */
    Map<String, Object> status();

    record SaveOutcome(boolean ok, Map<String, String> errors) {
    }
}
