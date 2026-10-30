package com.shenqianye.samsara.pet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个在线玩家最后一次“开始手持”驯服类物品的时间。
 *
 * <p>只在多主人宠物选择跟随目标时读取；每 10 tick 扫描一次，开销与在线人数线性相关。
 * 用“开始手持”的时间而不是持续刷新，保证多人同时手持时能稳定选出最后开始手持的人。
 */
public final class PetFollowTracker {
    private static final Map<UUID, Long> LAST_HELD = new ConcurrentHashMap<>();
    private static final Set<UUID> HOLDING = new HashSet<>();
    private static int counter;

    private PetFollowTracker() {
    }

    public static synchronized void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++counter < 10) {
            return;
        }
        counter = 0;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            boolean holding = holdsTamingLike(player.getMainHandItem())
                    || holdsTamingLike(player.getOffhandItem());
            if (holding && HOLDING.add(uuid)) {
                LAST_HELD.put(uuid, System.currentTimeMillis());
            } else if (!holding) {
                HOLDING.remove(uuid);
            }
        }
        HOLDING.retainAll(online);
        LAST_HELD.keySet().retainAll(online);
    }

    public static long lastHeld(UUID player) {
        Long value = LAST_HELD.get(player);
        return value == null ? 0L : value;
    }

    public static void clear() {
        LAST_HELD.clear();
        HOLDING.clear();
    }

    private static boolean holdsTamingLike(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(ItemTags.WOLF_FOOD) || stack.is(ItemTags.CAT_FOOD)
                || stack.is(ItemTags.PARROT_FOOD);
    }
}
