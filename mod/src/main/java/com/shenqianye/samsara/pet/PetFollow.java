package com.shenqianye.samsara.pet;

import com.shenqianye.samsara.identity.IdentityService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多主人宠物的跟随目标选择：
 * 主主人在线 → 主主人；否则手持对应驯服物品的最后一人；
 * 没人手持 → 最近的在线主人；都没有 → null（原地等待）。
 */
public final class PetFollow {
    private PetFollow() {
    }

    public static LivingEntity select(TamableAnimal pet, List<UUID> owners) {
        if (pet == null || owners == null || owners.isEmpty()) {
            return null;
        }
        MinecraftServer server = pet.level().getServer();
        if (server == null) {
            return null;
        }
        UUID primary = owners.get(0);
        ServerPlayer primaryOnline = IdentityService.getInstance().onlineFor(primary);
        if (primaryOnline != null && primaryOnline.level() == pet.level()) {
            return primaryOnline;
        }
        // 同一身份只保留一个在线玩家，避免 premium/offline 切换时重复候选。
        Map<UUID, ServerPlayer> online = new LinkedHashMap<>();
        for (UUID owner : owners) {
            ServerPlayer player = IdentityService.getInstance().onlineFor(owner);
            if (player != null && player.level() == pet.level()) {
                online.putIfAbsent(player.getUUID(), player);
            }
        }
        if (online.isEmpty()) {
            return null;
        }
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (ServerPlayer player : online.values()) {
            candidates.add(new Candidate(player.getUUID(),
                    IdentityService.getInstance().identityOf(player.getUUID()),
                    TamingItems.isHoldingTamingItem(pet, player),
                    PetFollowTracker.lastHeld(player.getUUID()),
                    player.distanceToSqr(pet)));
        }
        UUID chosen = choose(primary, candidates);
        return chosen == null ? null : online.get(chosen);
    }

    /** 候选：用于纯逻辑单测，与在线实体解耦。 */
    public record Candidate(UUID uuid, UUID data, boolean holding, long lastHeld,
                            double distance) {
    }

    /**
     * 纯选择规则：主主人 data 命中 → 它；否则最后手持 → 最近 → null。
     * 返回候选的实际 UUID。
     */
    public static UUID choose(UUID primary, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        UUID primaryData = null;
        for (Candidate candidate : candidates) {
            if (candidate.uuid().equals(primary)) {
                primaryData = candidate.data();
                break;
            }
        }
        if (primaryData != null) {
            for (Candidate candidate : candidates) {
                if (primaryData.equals(candidate.data())) {
                    return candidate.uuid();
                }
            }
        }
        Candidate holding = null;
        for (Candidate candidate : candidates) {
            if (!candidate.holding()) {
                continue;
            }
            if (holding == null || candidate.lastHeld() > holding.lastHeld()) {
                holding = candidate;
            }
        }
        if (holding != null) {
            return holding.uuid();
        }
        Candidate nearest = null;
        for (Candidate candidate : candidates) {
            if (nearest == null || candidate.distance() < nearest.distance()) {
                nearest = candidate;
            }
        }
        return nearest == null ? null : nearest.uuid();
    }
}
