package com.shenqianye.samsara.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 接口 → scope 映射：新增接口漏映射时必须回退到 OPS（最严），不能误放行。 */
class ApiScopeTest {

    @Test
    void readsRequireOnlyRead() {
        assertEquals(AgentScope.READ, ApiScope.required("GET", "/api/config"));
        assertEquals(AgentScope.READ, ApiScope.required("GET", "/api/identity/data/list"));
        assertEquals(AgentScope.READ, ApiScope.required("GET", "/api/world-stack"));
    }

    @Test
    void writesRequireDomainScope() {
        assertEquals(AgentScope.CONFIG, ApiScope.required("PUT", "/api/config"));
        assertEquals(AgentScope.ANNOUNCE, ApiScope.required("POST", "/api/announcements/send"));
        assertEquals(AgentScope.READ, ApiScope.required("POST", "/api/announcements/preview"));
        assertEquals(AgentScope.INVENTORY, ApiScope.required("POST", "/api/inventory/write"));
        assertEquals(AgentScope.INVENTORY, ApiScope.required("POST", "/api/items/build"));
        assertEquals(AgentScope.RCON, ApiScope.required("POST", "/api/server/console"));
        assertEquals(AgentScope.OPS, ApiScope.required("POST", "/api/audit/rollback"));
        assertEquals(AgentScope.READ, ApiScope.required("POST", "/api/audit/preview"));
        assertEquals(AgentScope.OPS, ApiScope.required("POST", "/api/diff/chunk/restore"));
        assertEquals(AgentScope.READ, ApiScope.required("POST", "/api/diff/view"));
    }

    @Test
    void tokenManagementNeedsOps() {
        assertEquals(AgentScope.OPS, ApiScope.required("POST", "/api/agent/tokens"));
    }

    @Test
    void unknownWriteFallsBackToStrictestScope() {
        assertEquals(AgentScope.OPS, ApiScope.required("POST", "/api/not-mapped-yet"));
        assertEquals(AgentScope.OPS, ApiScope.required("DELETE", "/api/whatever"));
    }

    @Test
    void identityMutationsRequireConfig() {
        assertEquals(AgentScope.CONFIG, ApiScope.required("POST", "/api/identity/rebind"));
        assertEquals(AgentScope.CONFIG, ApiScope.required("POST", "/api/identity/data/backup"));
        assertEquals(AgentScope.CONFIG, ApiScope.required("POST", "/api/permissions"));
        assertEquals(AgentScope.CONFIG, ApiScope.required("POST", "/api/output/preview"));
    }
}
