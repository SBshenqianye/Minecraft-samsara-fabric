package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.track.TrackRecorder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 回看目标解析：玩家名 / UUID / 身份组别名 / 该时段有轨迹的全体玩家。 */
final class ViewTargetResolver {

    private ViewTargetResolver() {
    }

    public record TargetRef(UUID uuid, String name) {
    }

    static TargetRef resolveTargetRef(MinecraftServer server, String spec) {
        ViewManager.TargetSpec target = resolveTarget(server, spec == null ? "" : spec.trim());
        return target == null ? null : new TargetRef(target.uuid(), target.name());
    }

    /** 身份组别名（命令层收藏/统计用）。 */
    static List<UUID> aliasesOf(UUID uuid) {
        List<UUID> aliases = new ArrayList<>(com.shenqianye.samsara.identity.IdentityService
                .getInstance().aliasesOf(uuid));
        UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .identityOf(uuid);
        if (canonical != null && !aliases.contains(canonical)) {
            aliases.add(canonical);
        }
        return aliases;
    }

    /** 解析逗号列表已经由命令层拆好的目标；重复目标去重。 */
    static List<ViewManager.TargetSpec> resolveTargets(MinecraftServer server, List<String> specs) {
        Map<UUID, ViewManager.TargetSpec> resolved = new LinkedHashMap<>();
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ViewManager.TargetSpec spec = resolveTarget(server, raw.trim());
            if (spec == null) {
                throw new IllegalArgumentException("找不到玩家: " + raw
                        + "（可用 /samsara debug fakeplayers 注入测试名单）");
            }
            resolved.putIfAbsent(spec.uuid(), spec);
        }
        return new ArrayList<>(resolved.values());
    }

    static ViewManager.TargetSpec resolveTarget(MinecraftServer server, String spec) {
        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(spec);
        UUID targetUuid = onlineTarget != null ? onlineTarget.getUUID() : resolvePlayerId(server, spec);
        if (targetUuid == null) {
            return null;
        }
        UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .identityOf(targetUuid);
        if (canonical == null) {
            canonical = targetUuid;
        }
        String name = onlineTarget != null ? onlineTarget.getScoreboardName()
                : displayName(server, targetUuid, spec);
        List<UUID> aliases = new ArrayList<>(com.shenqianye.samsara.identity.IdentityService
                .getInstance().aliasesOf(targetUuid));
        if (!aliases.contains(canonical)) {
            aliases.add(canonical);
        }
        return new ViewManager.TargetSpec(canonical, name, List.copyOf(aliases));
    }

    /** 该时段有轨迹的全体玩家（按身份组合并多个账号入口）。 */
    static List<ViewManager.TargetSpec> discoverTargets(MinecraftServer server, List<Path> roots,
                                                        int cycleId, long from, long to) {
        List<ViewTrackReader.AvailablePlayer> found =
                ViewTrackReader.availablePlayers(roots, cycleId, from, to);
        Map<UUID, LinkedHashSet<UUID>> groups = new LinkedHashMap<>();
        for (ViewTrackReader.AvailablePlayer player : found) {
            UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                    .identityOf(player.uuid());
            if (canonical == null) {
                canonical = player.uuid();
            }
            groups.computeIfAbsent(canonical, id -> new LinkedHashSet<>()).add(player.uuid());
        }
        List<ViewManager.TargetSpec> out = new ArrayList<>();
        for (Map.Entry<UUID, LinkedHashSet<UUID>> entry : groups.entrySet()) {
            UUID canonical = entry.getKey();
            UUID displayId = entry.getValue().iterator().next();
            String name = displayName(server, displayId, canonical.toString());
            List<UUID> aliases = new ArrayList<>(entry.getValue());
            aliases.addAll(com.shenqianye.samsara.identity.IdentityService.getInstance()
                    .aliasesOf(canonical));
            if (!aliases.contains(canonical)) {
                aliases.add(canonical);
            }
            out.add(new ViewManager.TargetSpec(canonical, name,
                    List.copyOf(new LinkedHashSet<>(aliases))));
        }
        return out;
    }

    /** 按 UUID / 名字在目标列表里查找（--follow）。 */
    static ViewManager.TargetSpec findTarget(List<ViewManager.TargetSpec> targets, String spec) {
        for (ViewManager.TargetSpec target : targets) {
            if (target.uuid().toString().equalsIgnoreCase(spec)
                    || target.name().equalsIgnoreCase(spec)) {
                return target;
            }
        }
        return null;
    }

    /** 目标解析：直接 UUID / 在线名 / 注册名单 / usercache。 */
    static UUID resolvePlayerId(MinecraftServer server, String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(spec.trim());
        } catch (IllegalArgumentException ignored) {
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(spec);
        if (online != null) {
            return online.getUUID();
        }
        UUID id = TrackRecorder.resolveUuid(server, spec);
        if (id != null) {
            return id;
        }
        try {
            return server.services().nameToIdCache().get(spec).map(n -> n.id()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    static String displayName(MinecraftServer server, UUID uuid, String fallback) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getScoreboardName();
        }
        try {
            var found = server.services().nameToIdCache().get(uuid);
            if (found.isPresent()) {
                return found.get().name();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
