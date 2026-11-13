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
 * OP / owner 权限口令：只管权限，不管账号和游玩。
 *
 * <p>受保护名单 = {@code ops.json} 里的名字 + {@code permissions.yml} 的 owners。
 * 离线模式下这些名字进服后处于 pending：普通游玩（移动、聊天、建造、普通命令）
 * 完全不受影响，只有需要 OP/owner 权限的命令会被拦下。
 *
 * <p>密码答错不踢人、不封禁、不计数封顶，可以无限次重新输入 /login；
 * 未验证期间玩家一直可以正常游玩，只是拿不到 OP/owner 权限。
 */
public final class OpAuthService {
    private static final OpAuthService INSTANCE = new OpAuthService();

    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastReminder = new ConcurrentHashMap<>();
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
        this.lastReminder.clear();
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
        lastReminder.remove(uuid);
        String name = player.getGameProfile().name();
        OpAuthStore.Entry entry = store.get(name);
        String message;
        if (entry != null && entry.hasPassword()) {
            message = "你的账号受 OP/owner 保护，请输入 /login <密码> 解锁权限（答错不会被踢出，可反复尝试；未解锁不影响正常游玩）。";
        } else if (entry != null && entry.hasCode()) {
            message = "你的账号受 OP/owner 保护，请用 /register <注册码> <密码> <密码> 注册（未解锁不影响正常游玩）。";
        } else {
            message = "你的账号受 OP/owner 保护，请联系管理员生成一次性注册码。";
        }
        player.sendSystemMessage(ChatFormat.formatComponent(message));
    }

    public void onDisconnect(UUID uuid) {
        if (uuid != null) {
            pending.remove(uuid);
            authenticated.remove(uuid);
            lastReminder.remove(uuid);
        }
    }

    /** 未验证期间仅做温和提醒，绝不踢出或封禁；pending 一直保留直到答对密码。 */
    public void tick(MinecraftServer current) {
        if (store == null || server == null || !enabled()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = Math.max(15, store.timeoutSeconds()) * 1000L;
        for (Map.Entry<UUID, Long> item : pending.entrySet()) {
            if (now - item.getValue() < timeout) {
                continue;
            }
            Long last = lastReminder.get(item.getKey());
            if (last != null) {
                continue;
            }
            ServerPlayer player = current.getPlayerList().getPlayer(item.getKey());
            if (player == null) {
                continue;
            }
            lastReminder.put(item.getKey(), now);
            player.sendSystemMessage(ChatFormat.formatComponent(
                    "OP 权限尚未解锁；可随时输入 /login <密码> 重新尝试，不影响正常游玩。"));
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
            lastReminder.remove(uuid);
            player.sendSystemMessage(ChatFormat.formatComponent("登录成功，OP/owner 命令已解锁。"));
        } else {
            // 密码只决定权限，不决定能否游玩：保持 pending，允许无限次重试，绝不踢出。
            player.sendSystemMessage(ChatFormat.formatComponent(
                    "密码错误，请重新输入 /login <密码>（不会被踢出，可继续游玩）。"));
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

    /** 认证相关命令根：这些命令在 pending 时始终放行。 */
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
