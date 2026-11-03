package com.shenqianye.samsara.web;

import com.shenqianye.samsara.rewind.ChunkRestoreService;
import com.shenqianye.samsara.rewind.DiffViewService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Web 端单区块回溯/回看与全局回看（F-165 / F-166）。
 * <p>
 * 所有会读取 ServerLevel/区块的操作都切回服务端线程执行；HTTP 线程只等待结果。
 * 回溯执行必须显式 {@code confirm=true}；预览不会写世界。
 */
public final class ChunkDiffWebService {
    private final MinecraftServer server;

    public ChunkDiffWebService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> summary(String stack, String dim, Integer cx, Integer cz,
                                       String to, boolean load) {
        if (cx == null || cz == null) {
            return error("参数应为 cx=<int>&cz=<int>");
        }
        return onMain(() -> ChunkRestoreService.getInstance()
                .summaryMap(server, stack, dim, cx, cz, to, load));
    }

    public Map<String, Object> restore(Map<String, Object> body) {
        Integer cx = intValue(body.get("cx"));
        Integer cz = intValue(body.get("cz"));
        if (cx == null || cz == null) {
            return error("缺少 cx/cz");
        }
        String stack = stringValue(body.get("stack"));
        String dim = stringValue(body.get("dim"));
        String to = stringValue(body.get("to"));
        boolean preview = boolValue(body.get("preview"));
        boolean confirm = boolValue(body.get("confirm"));
        if (preview) {
            return onMain(() -> {
                Map<String, Object> out = new LinkedHashMap<>(
                        ChunkRestoreService.getInstance().summaryMap(server, stack, dim, cx, cz, to, true));
                out.put("preview", true);
                return out;
            });
        }
        if (!confirm) {
            return error("执行回溯需要 confirm=true（请先预览）");
        }
        return onMain(() -> ChunkRestoreService.getInstance()
                .restore(server, stack, dim, cx, cz, to));
    }

    public Map<String, Object> viewStatus() {
        return onMain(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("status", DiffViewService.getInstance().status());
            out.put("sessions", DiffViewService.getInstance().activeSessions());
            return out;
        });
    }

    public Map<String, Object> view(Map<String, Object> body) {
        String action = stringValue(body.get("action"));
        if ("stop".equalsIgnoreCase(action)) {
            return onMain(() -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("message", DiffViewService.getInstance().stop(server, null,
                        stringValue(body.get("player")), true));
                return out;
            });
        }
        String playerName = stringValue(body.get("player"));
        ServerPlayer player = playerName == null || playerName.isBlank() ? null
                : server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            return error("找不到在线观看者：" + playerName);
        }
        Integer cx = intValue(body.get("cx"));
        Integer cz = intValue(body.get("cz"));
        String stack = stringValue(body.get("stack"));
        String dim = stringValue(body.get("dim"));
        String from = stringValue(body.get("from"));
        String to = stringValue(body.get("to"));
        double speed = doubleValue(body.get("speed"), 1.0);
        boolean global = boolValue(body.get("global"));
        if (!global && (cx == null || cz == null)) {
            return error("单区块回看缺少 cx/cz");
        }
        int radius = intValue(body.get("radius")) == null ? DiffViewService.DEFAULT_RADIUS
                : intValue(body.get("radius"));
        return onMain(() -> {
            String message = global
                    ? DiffViewService.getInstance().startGlobal(server, player, dim, from, to,
                            speed, radius)
                    : DiffViewService.getInstance().startChunk(server, player, stack, dim,
                            cx, cz, from, to, speed);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", message);
            return out;
        });
    }

    private <T> T onMain(java.util.concurrent.Callable<T> call) {
        try {
            if (server.isSameThread()) {
                return call.call();
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(call.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            @SuppressWarnings("unchecked")
            T fallback = (T) error("服务器线程执行失败: " + e.getMessage());
            return fallback;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value)));
    }
}
