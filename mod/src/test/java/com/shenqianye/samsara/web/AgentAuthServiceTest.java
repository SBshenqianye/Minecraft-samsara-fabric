package com.shenqianye.samsara.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthServiceTest {
    @Test
    void adminTokenGetsAllScopes() {
        AgentAuthService service = AgentAuthService.getInstance();
        AgentAuthService.AuthContext context = service.authenticate("Bearer admin-token", "admin-token");
        assertNotNull(context);
        assertTrue(context.admin());
        for (AgentScope scope : AgentScope.values()) {
            assertTrue(context.has(scope));
        }
    }

    @Test
    void scopeAndRateLimitAreEnforced() {
        AgentAuthService service = AgentAuthService.getInstance();
        AgentAuthService.AuthContext readOnly = new AgentAuthService.AuthContext(
                "read-only-test", java.util.Set.of(AgentScope.READ), false);
        assertTrue(readOnly.has(AgentScope.READ));
        assertFalse(readOnly.has(AgentScope.ANNOUNCE));
        for (int i = 0; i < service.limit(AgentScope.READ); i++) {
            assertTrue(service.allow(readOnly, AgentScope.READ));
        }
        assertFalse(service.allow(readOnly, AgentScope.READ));
    }

    @Test
    void invalidTokenRejectedAndScopeIdsAreStable() {
        AgentAuthService service = AgentAuthService.getInstance();
        assertNull(service.authenticate("Bearer wrong", "admin-token"));
        assertNull(service.authenticate(null, "admin-token"));
        assertTrue(AgentScope.ids().contains("read"));
        assertTrue(AgentScope.ids().contains("announce"));
        assertTrue(AgentScope.ids().contains("config"));
        assertTrue(AgentScope.ids().contains("ops"));
        assertTrue(AgentScope.ids().contains("rcon"));
        assertFalse(AgentScope.ids().contains("admin"));
    }
}
