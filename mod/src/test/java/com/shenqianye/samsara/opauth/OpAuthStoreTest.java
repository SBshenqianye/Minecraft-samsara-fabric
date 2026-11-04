package com.shenqianye.samsara.opauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OP/owner 登录凭据：PBKDF2、一次性注册码、重置与持久化。 */
class OpAuthStoreTest {
    @TempDir
    Path temp;

    @Test
    void passwordAndCodeFlow() {
        Path file = temp.resolve("samsara/op-auth.yml");
        OpAuthStore store = new OpAuthStore(file);
        store.load();

        store.setPassword("Alice", "password123");
        assertTrue(store.verifyPassword("Alice", "password123"));
        assertFalse(store.verifyPassword("Alice", "wrong"));
        assertFalse(store.verifyPassword("Bob", "password123"));

        String code = store.generateCode("Bob");
        assertNotNull(code);
        assertEquals(10, code.length());
        assertEquals(OpAuthStore.CodeResult.WRONG, store.consumeCode("Bob", "AAAAAAAAAA"));
        assertEquals(OpAuthStore.CodeResult.OK, store.consumeCode("Bob", code));
        assertEquals(OpAuthStore.CodeResult.NO_CODE, store.consumeCode("Bob", code));

        String reset = store.reset("Alice");
        assertFalse(store.verifyPassword("Alice", "password123"));
        assertEquals(OpAuthStore.CodeResult.OK, store.consumeCode("Alice", reset));
        store.setPassword("Alice", "newpassword");
        assertTrue(store.verifyPassword("Alice", "newpassword"));
    }

    @Test
    void codeIsInvalidatedAfterFiveWrongAttempts() {
        OpAuthStore store = new OpAuthStore(temp.resolve("op-auth.yml"));
        store.load();
        store.generateCode("Carol");
        for (int i = 0; i < 4; i++) {
            assertEquals(OpAuthStore.CodeResult.WRONG, store.consumeCode("Carol", "BADCODE123"));
        }
        assertEquals(OpAuthStore.CodeResult.TOO_MANY, store.consumeCode("Carol", "BADCODE123"));
        assertEquals(OpAuthStore.CodeResult.NO_CODE, store.consumeCode("Carol", "BADCODE123"));
    }

    @Test
    void persistedFileReloads() {
        Path file = temp.resolve("op-auth.yml");
        OpAuthStore store = new OpAuthStore(file);
        store.load();
        store.setPassword("Dave", "password123");
        String code = store.generateCode("Eve");

        OpAuthStore reloaded = new OpAuthStore(file);
        reloaded.load();
        assertTrue(reloaded.verifyPassword("Dave", "password123"));
        assertTrue(reloaded.get("Eve").hasCode());
        assertEquals(OpAuthStore.CodeResult.OK, reloaded.consumeCode("Eve", code));
    }
}
