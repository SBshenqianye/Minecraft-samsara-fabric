package com.shenqianye.samsara.identity;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 身份审计：{@code identity/audit/identity-YYYY-MM-DD.jsonl}，只追加。 */
public final class IdentityAudit {
    private static final Gson GSON = new Gson();

    private IdentityAudit() {
    }

    public static void log(Path identityRoot, String event, String actor, UUID uuid,
                           String detail) {
        if (identityRoot == null) {
            return;
        }
        try {
            Path dir = identityRoot.resolve("audit");
            Files.createDirectories(dir);
            Path file = dir.resolve("identity-" + LocalDate.now() + ".jsonl");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", 1);
            row.put("t", System.currentTimeMillis());
            row.put("ev", event);
            row.put("actor", actor == null ? "" : actor);
            row.put("uuid", uuid == null ? null : uuid.toString());
            row.put("detail", detail == null ? "" : detail);
            Files.writeString(file, GSON.toJson(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 审计失败不能阻断身份流程。
        }
    }
}
