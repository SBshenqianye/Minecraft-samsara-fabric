package com.shenqianye.samsara.pet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 宠物多主人去重、上限与 data UUID 归属。 */
class PetOwnersTest {
    @Test
    void normalizeKeepsPrimaryFirstAndDeduplicates() {
        UUID primary = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<UUID> owners = PetOwners.normalize(primary, List.of(other, primary, other));
        assertEquals(List.of(primary, other), owners);
    }

    @Test
    void sameIdentityGroupDoesNotOccupyTwoSlots() {
        UUID data = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        List<UUID> owners = new ArrayList<>(List.of(premium));
        assertEquals(PetOwners.AddResult.ALREADY_OWNER,
                PetOwners.addOwner(owners, offline, 32, uuid -> uuid.equals(premium)
                        || uuid.equals(offline) ? data : uuid));
        assertEquals(1, owners.size());
    }

    @Test
    void limitIsEnforced() {
        List<UUID> owners = new ArrayList<>();
        owners.add(UUID.randomUUID());
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        assertEquals(PetOwners.AddResult.ADDED,
                PetOwners.addOwner(owners, second, 2, uuid -> uuid));
        assertEquals(PetOwners.AddResult.LIMIT_REACHED,
                PetOwners.addOwner(owners, third, 2, uuid -> uuid));
        assertEquals(2, owners.size());
    }

    @Test
    void isOwnerResolvesDataUuid() {
        UUID data = UUID.randomUUID();
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        List<UUID> owners = List.of(premium);
        assertTrue(PetOwners.isOwner(owners, offline,
                uuid -> uuid.equals(premium) || uuid.equals(offline) ? data : uuid));
        assertFalse(PetOwners.isOwner(owners, UUID.randomUUID(), uuid -> uuid));
    }

    @Test
    void followPriorityIsPrimaryThenLatestHeldThenNearest() {
        UUID data = UUID.randomUUID();
        UUID primary = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // 主主人不在线；a 手持较早，b 手持较晚。
        List<PetFollow.Candidate> candidates = List.of(
                new PetFollow.Candidate(a, a, true, 10L, 1),
                new PetFollow.Candidate(b, b, true, 20L, 9));
        assertEquals(b, PetFollow.choose(primary, candidates));

        List<PetFollow.Candidate> noneHolding = List.of(
                new PetFollow.Candidate(a, a, false, 10L, 8),
                new PetFollow.Candidate(b, b, false, 20L, 2));
        assertEquals(b, PetFollow.choose(primary, noneHolding));

        List<PetFollow.Candidate> withPrimary = List.of(
                new PetFollow.Candidate(primary, data, false, 0, 50),
                new PetFollow.Candidate(b, b, true, 20L, 1));
        assertEquals(primary, PetFollow.choose(primary, withPrimary));
    }
}
