package com.shenqianye.samsara.pet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 宠物多主人纯逻辑：主主人 + 额外主人，按 data UUID 去重，受上限约束。
 *
 * <p>NBT 存实际 UUID；去重时由调用方提供 {@code identityOf}，所以同一身份组的
 * premium/offline 不会重复占坑。
 */
public final class PetOwners {
    private PetOwners() {
    }

    public enum AddResult {
        ADDED, ALREADY_OWNER, LIMIT_REACHED
    }

    /** 合并主主人与额外列表（主主人永远排第一）。 */
    public static List<UUID> normalize(UUID primary, List<UUID> stored) {
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        if (primary != null) {
            out.add(primary);
        }
        if (stored != null) {
            for (UUID uuid : stored) {
                if (uuid != null) {
                    out.add(uuid);
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean isOwner(List<UUID> owners, UUID candidate,
                                  UnaryOperator<UUID> dataOf) {
        if (candidate == null) {
            return false;
        }
        UUID data = dataOf == null ? candidate : dataOf.apply(candidate);
        for (UUID owner : owners) {
            UUID ownerData = dataOf == null ? owner : dataOf.apply(owner);
            if (data != null && data.equals(ownerData)) {
                return true;
            }
        }
        return false;
    }

    public static AddResult addOwner(List<UUID> owners, UUID candidate, int maxOwners,
                                     UnaryOperator<UUID> dataOf) {
        if (candidate == null) {
            return AddResult.ALREADY_OWNER;
        }
        if (isOwner(owners, candidate, dataOf)) {
            return AddResult.ALREADY_OWNER;
        }
        if (owners.size() >= Math.max(1, maxOwners)) {
            return AddResult.LIMIT_REACHED;
        }
        owners.add(candidate);
        return AddResult.ADDED;
    }
}
