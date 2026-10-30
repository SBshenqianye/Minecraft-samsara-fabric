package com.shenqianye.samsara.pet;

import java.util.List;
import java.util.UUID;

/** Mixin 暴露给交互/跟随逻辑的额外主人接口。 */
public interface TamableAnimalOwners {
    List<UUID> samsara$owners();

    List<UUID> samsara$allOwners();

    PetOwners.AddResult samsara$addOwner(UUID uuid);

    void samsara$setOwners(List<UUID> owners);
}
