package com.shenqianye.samsara.web;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Web 控制台：在服务器线程执行原版/模组命令并捕获文本输出。
 *
 * <p>不做 RCON 网络客户端，也不把 RCON 密码带进浏览器；权限等同控制台
 * （{@link PermissionSet#ALL_PERMISSIONS}），仅供已持有管理端 token 的本机页面使用。
 * 命令执行是同步的，超时只影响 HTTP 返回，不会中断服务器 tick。
 */
public final class WebConsoleService {
    private final MinecraftServer server;

    public WebConsoleService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> execute(String command) {
        String raw = command == null ? "" : command.trim();
        if (raw.startsWith("/")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isBlank()) {
            return fail("命令不能为空");
        }
        if (raw.length() > 4000) {
            return fail("命令过长（最多 4000 字符）");
        }
        if (server == null) {
            return fail("服务器未启动");
        }
        long started = System.currentTimeMillis();
        try {
            return server.isSameThread() ? run(raw, started) : runOnServer(raw, started);
        } catch (Exception e) {
            return fail("执行失败：" + e.getMessage());
        }
    }

    /** Brigadier Tab 补全：返回替换区间与候选（含 tooltip）。 */
    public Map<String, Object> complete(String command, Integer requestedCursor) {
        if (server == null) {
            return fail("服务器未启动");
        }
        String raw = command == null ? "" : command;
        int cursor = requestedCursor == null ? raw.length() : requestedCursor;
        cursor = Math.max(0, Math.min(cursor, raw.length()));
        if (raw.startsWith("/")) {
            raw = raw.substring(1);
            cursor = Math.max(0, cursor - 1);
        }
        if (raw.length() > 4000) {
            return fail("命令过长（最多 4000 字符）");
        }
        final String input = raw;
        final int at = cursor;
        try {
            return server.isSameThread() ? completeNow(input, at) : completeOnServer(input, at);
        } catch (Exception e) {
            return fail("补全失败：" + e.getMessage());
        }
    }

    private Map<String, Object> completeOnServer(String input, int cursor) throws Exception {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(completeNow(input, cursor));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(4, TimeUnit.SECONDS);
    }

    private Map<String, Object> completeNow(String input, int cursor) {
        CommandSourceStack source = new CommandSourceStack(new CaptureSource(),
                Vec3.ZERO, new Vec2(0, 0), server.overworld(), PermissionSet.ALL_PERMISSIONS,
                Component.literal("Web RCON"), server);
        ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher()
                .parse(input, source);
        Suggestions suggestions = server.getCommands().getDispatcher()
                .getCompletionSuggestions(parsed, cursor).join();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            if (rows.size() >= 200) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", suggestion.getText());
            row.put("tooltip", suggestion.getTooltip() == null
                    ? "" : suggestion.getTooltip().getString());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("input", input);
        out.put("cursor", cursor);
        out.put("range", Map.of("start", suggestions.getRange().getStart(),
                "end", suggestions.getRange().getEnd()));
        out.put("suggestions", rows);
        return out;
    }

    private Map<String, Object> runOnServer(String command, long started) throws Exception {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(run(command, started));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(8, TimeUnit.SECONDS);
    }

    private Map<String, Object> run(String command, long started) {
        CaptureSource capture = new CaptureSource();
        CommandSourceStack source = new CommandSourceStack(capture,
                Vec3.ZERO, new Vec2(0, 0), server.overworld(), PermissionSet.ALL_PERMISSIONS,
                Component.literal("Web RCON"), server);
        boolean stopping = command.equals("stop") || command.startsWith("stop ");
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (RuntimeException e) {
            capture.lines.add("命令异常：" + e.getMessage());
        }
        if (capture.lines.isEmpty()) {
            capture.lines.add(stopping ? "已请求停止服务器。" : "命令已执行（无文本输出）。");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("command", command);
        out.put("output", capture.lines);
        out.put("timeMs", System.currentTimeMillis() - started);
        out.put("stopping", stopping);
        return out;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        out.put("output", List.of(message));
        return out;
    }

    private static final class CaptureSource implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            if (component != null) {
                String text = component.getString();
                if (!text.isBlank()) lines.add(text);
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
