package com.shenqianye.samsara.opauth;

import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.permission.PermissionConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP / owner 离线登录门禁。
 *
 * <p>目标不是识别玩家，而是防止别人用受保护玩家的名字进服后直接拿到
 * OP / owner 命令权限。普通玩家完全不受影响。
 *
 * <p>受保护名单 = {@code ops.json} 里的名字 + {@code permissions.yml} 的 owners。
 * 离线模式下，这些名字进服后处于 pending：除登录/注册/帮助外，所有命令都会被
 * {@code CommandsMixin} 拦下，samsara 权限检查也会降级为 USER。
 */
public final class OpAuthService {
    private static final OpAuthService INSTANCE = new OpAuthService();

    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> loginAttempts = new ConcurrentHashMap<>();
    private MinecraftServer server;
    private OpAuthStore store;
    private int tickCounter;

    private OpAuthService() {
    }

    public static OpAuthService getInstance() {
        return INSTANCE;
    }

    public synchronized void init(MinecraftServer server) {
        this.server = server;
        Path file = FabricLoader.getInstance().getConfigDir()
                .resolve("samsara").resolve("op-auth.yml");
        this.store = new OpAuthStore(file);
        this.store.load();
        this.pending.clear();
        this.authenticated.clear();
    }

    public synchronized boolean enabled() {
        return store != null && store.enabled();
    }

    public synchronized boolean ready() {
        return store != null;
    }

    public synchronized int codeMinutes() {
        return store == null ? 30 : store.codeMinutes();
    }

    public boolean isPending(ServerPlayer player) {
        return player != null && isPending(player.getUUID());
    }

    public boolean isPending(UUID uuid) {
        return uuid != null && pending.containsKey(uuid);
    }

    public boolean isProtected(ServerPlayer player) {
        if (player == null || server == null) {
            return false;
        }
        String name = player.getGameProfile().name();
        if (PermissionConfig.getInstance().isOwner(name)) {
            return true;
        }
        if (server.getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
            return true;
        }
        // 身份同步可能改写 UUID，再按名字兜底一次。
        for (var entry : server.getPlayerList().getOps().getEntries()) {
            if (entry.getUser().name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 进服：受保护且离线登录时进入 pending，并提示登录方式。 */
    public void onJoin(ServerPlayer player) {
        if (player == null || !enabled() || server == null || server.usesAuthentication()) {
            return;
        }
        UUID uuid = player.getUUID();
        if (!isProtected(player)) {
            pending.remove(uuid);
            authenticated.remove(uuid);
            return;
        }
        pending.put(uuid, System.currentTimeMillis());
        authenticated.remove(uuid);
        loginAttempts.remove(uuid);
        String name = player.getGameProfile().name();
        OpAuthStore.Entry entry = store.get(name);
        String message;
        if (entry != null && entry.hasPassword()) {
            message = "你的账号受 OP/owner 保护，请先输入 /login <密码>。";
        } else if (entry != null && entry.hasCode()) {
            message = "你的账号受 OP/owner 保护，请用 /register <注册码> <密码> <密码> 注册。";
        } else {
            message = "你的账号受 OP/owner 保护，请联系管理员生成一次性注册码。";
        }
        player.sendSystemMessage(ChatFormat.formatComponent(message));
    }

    public void onDisconnect(UUID uuid) {
        if (uuid != null) {
            pending.remove(uuid);
            authenticated.remove(uuid);
            loginAttempts.remove(uuid);
        }
    }

    /** 每秒检查一次登录超时。 */
    public void tick(MinecraftServer current) {
        if (store == null || server == null || !enabled()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = store.timeoutSeconds() * 1000L;
        for (Map.Entry<UUID, Long> item : pending.entrySet()) {
            if (now - item.getValue() < timeout) {
                continue;
            }
            ServerPlayer player = current.getPlayerList().getPlayer(item.getKey());
            pending.remove(item.getKey());
            if (player != null) {
                player.connection.disconnect(ChatFormat.formatComponent(
                        "登录超时：请重新进服并输入 /login <密码>。"));
            }
        }
    }

    public void login(ServerPlayer player, String password) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (!isPending(uuid)) {
            player.sendSystemMessage(ChatFormat.formatComponent("你当前不需要登录。"));
            return;
        }
        String name = player.getGameProfile().name();
        if (store.verifyPassword(name, password)) {
            pending.remove(uuid);
            authenticated.add(uuid);
            loginAttempts.remove(uuid);
            player.sendSystemMessage(ChatFormat.formatComponent("登录成功，OP/owner 命令已解锁。"));
        } else {
            int attempts = loginAttempts.merge(uuid, 1, Integer::sum);
            if (attempts >= 5) {
                pending.remove(uuid);
                loginAttempts.remove(uuid);
                player.connection.disconnect(ChatFormat.formatComponent(
                        "密码错误次数过多，请重新进服后再试。"));
            } else {
                player.sendSystemMessage(ChatFormat.formatComponent(
                        "密码错误（还可尝试 " + (5 - attempts) + " 次）。"));
            }
        }
    }

    public void register(ServerPlayer player, String code, String password, String confirm) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (!isPending(uuid)) {
            player.sendSystemMessage(ChatFormat.formatComponent("你当前不需要注册。"));
            return;
        }
        if (password == null || password.length() < 8) {
            player.sendSystemMessage(ChatFormat.formatComponent("密码至少 8 位。"));
            return;
        }
        if (!password.equals(confirm)) {
            player.sendSystemMessage(ChatFormat.formatComponent("两次输入的密码不一致。"));
            return;
        }
        String name = player.getGameProfile().name();
        OpAuthStore.CodeResult result = store.consumeCode(name, code);
        switch (result) {
            case OK -> {
                store.setPassword(name, password);
                pending.remove(uuid);
                authenticated.add(uuid);
                player.sendSystemMessage(ChatFormat.formatComponent(
                        "注册成功，OP/owner 命令已解锁。"));
            }
            case NO_CODE -> player.sendSystemMessage(ChatFormat.formatComponent(
                    "没有可用的注册码，请联系管理员生成。"));
            case EXPIRED -> player.sendSystemMessage(ChatFormat.formatComponent(
                    "注册码已过期，请联系管理员重新生成。"));
            case TOO_MANY -> player.sendSystemMessage(ChatFormat.formatComponent(
                    "注册码尝试次数过多，已作废；请联系管理员重新生成。"));
            case WRONG -> player.sendSystemMessage(ChatFormat.formatComponent("注册码错误。"));
        }
    }

    public synchronized String generateCode(String name) {
        return store == null ? null : store.generateCode(name);
    }

    public synchronized String reset(String name) {
        return store == null ? null : store.reset(name);
    }

    public synchronized boolean setPassword(String name, String password) {
        if (store == null || password == null || password.length() < 8) {
            return false;
        }
        store.setPassword(name, password);
        return true;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("timeoutSeconds", store == null ? 0 : store.timeoutSeconds());
        List<Map<String, Object>> entries = new ArrayList<>();
        if (store != null) {
            for (OpAuthStore.Entry entry : store.all()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.name);
                item.put("hasPassword", entry.hasPassword());
                item.put("hasCode", entry.hasCode());
                item.put("codeExpires", entry.codeExpires);
                entries.add(item);
            }
        }
        out.put("entries", entries);
        out.put("pending", pending.size());
        return out;
    }

    /** pending 状态下允许执行的命令根；其余全部拦截。 */
    public static boolean isAuthCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return false;
        }
        String[] parts = normalized.split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.equals("l") || root.equals("login")
                || root.equals("reg") || root.equals("register")
                || root.equals("help")) {
            return true;
        }
        if (root.equals("samsara")) {
            if (parts.length == 1) {
                return true;
            }
            String sub = parts[1].toLowerCase(Locale.ROOT);
            return sub.equals("login") || sub.equals("register") || sub.equals("help");
        }
        return false;
    }
}
