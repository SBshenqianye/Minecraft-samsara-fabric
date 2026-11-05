package com.shenqianye.samsara.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTokenStoreTest {
    @TempDir
    Path dir;

    @Test
    void createListsAndRevokesWithoutExposingToken() {
        AgentTokenStore store = new AgentTokenStore(dir.resolve("agent-tokens.json"));
        Map<String, Object> created = store.create("ops-agent",
                List.of("read", "announce", "ops"));
        assertTrue(Boolean.TRUE.equals(created.get("ok")), created.toString());
        String token = String.valueOf(created.get("token"));
        assertTrue(token.startsWith("sk_"));

        AgentTokenStore.TokenRecord record = store.findByToken(token);
        assertNotNull(record);
        assertEquals("ops-agent", record.name);
        assertTrue(record.scopeSet().contains(AgentScope.ANNOUNCE));
        assertTrue(record.scopeSet().contains(AgentScope.OPS));
        assertFalse(record.scopeSet().contains(AgentScope.CONFIG));

        List<Map<String, Object>> publicList = store.listPublic();
        assertEquals(1, publicList.size());
        assertFalse(publicList.get(0).containsKey("token"));
        assertFalse(publicList.get(0).containsKey("tokenHash"));

        Map<String, Object> revoked = store.revoke("ops-agent");
        assertTrue(Boolean.TRUE.equals(revoked.get("ok")));
        assertNull(store.findByToken(token));
    }

    @Test
    void duplicateNameAndUnknownScopeRejected() {
        AgentTokenStore store = new AgentTokenStore(dir.resolve("agent-tokens.json"));
        assertTrue(Boolean.TRUE.equals(store.create("agent", List.of("read")).get("ok")));
        assertFalse(Boolean.TRUE.equals(store.create("agent", List.of("read")).get("ok")));
        assertFalse(Boolean.TRUE.equals(store.create("other", List.of("root")).get("ok")));
        assertFalse(Boolean.TRUE.equals(store.create("admin", List.of("read")).get("ok")));
    }
}
