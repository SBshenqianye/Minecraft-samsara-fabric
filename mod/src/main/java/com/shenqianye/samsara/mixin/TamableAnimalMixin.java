package com.shenqianye.samsara.mixin;

import com.mojang.serialization.Codec;
import com.shenqianye.samsara.identity.IdentityService;
import com.shenqianye.samsara.pet.PetFollow;
import com.shenqianye.samsara.pet.PetOwners;
import com.shenqianye.samsara.pet.TamableAnimalOwners;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 宠物多主人：保留原版 {@code Owner} 标量，额外主人写 {@code samsara:owners}。
 *
 * <p>原版只读 {@code Owner}，不会因为未知 tag 报错；纯原版保存可能丢失额外主人，
 * 主主人保留，这是设计接受的降级。
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin implements TamableAnimalOwners {
    @Unique
    private final List<UUID> samsara$owners = new ArrayList<>();

    @Shadow
    public abstract EntityReference<LivingEntity> getOwnerReference();

    @Shadow
    @Final
    protected static EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNERUUID_ID;

    @Shadow
    public abstract void setOwner(LivingEntity owner);

    @Shadow
    public abstract boolean isTame();

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void samsara$readOwners(ValueInput input, CallbackInfo ci) {
        List<UUID> stored = new ArrayList<>();
        for (String text : input.listOrEmpty("samsara:owners", Codec.STRING)) {
            try {
                stored.add(UUID.fromString(text));
            } catch (RuntimeException ignored) {
            }
        }
        samsara$owners.clear();
        samsara$owners.addAll(PetOwners.normalize(samsara$primaryOwner(), stored));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void samsara$writeOwners(ValueOutput output, CallbackInfo ci) {
        List<UUID> all = samsara$allOwners();
        if (all.isEmpty()) {
            return;
        }
        output.store("samsara:owners_version", Codec.INT, 1);
        var list = output.list("samsara:owners", Codec.STRING);
        for (UUID owner : all) {
            list.add(owner.toString());
        }
    }

    @Inject(method = "isOwnedBy", at = @At("HEAD"), cancellable = true)
    private void samsara$isOwnedBy(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            return;
        }
        List<UUID> all = samsara$allOwners();
        if (all.size() > 1 && PetOwners.isOwner(all, entity.getUUID(),
                IdentityService.getInstance()::identityOf)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setOwner", at = @At("HEAD"), cancellable = true)
    private void samsara$appendOwner(LivingEntity owner, CallbackInfo ci) {
        if (owner == null) {
            return;
        }
        UUID primary = samsara$primaryOwner();
        if (primary != null && !primary.equals(owner.getUUID())) {
            samsara$addOwner(owner.getUUID());
            ci.cancel();
        }
    }

    @Inject(method = "setOwner", at = @At("TAIL"))
    private void samsara$rememberOwner(LivingEntity owner, CallbackInfo ci) {
        if (owner != null) {
            samsara$addOwner(owner.getUUID());
        }
    }

    @Inject(method = "tame", at = @At("TAIL"))
    private void samsara$tame(Player player, CallbackInfo ci) {
        if (player != null) {
            samsara$addOwner(player.getUUID());
        }
    }

    @Inject(method = "getOwnerReference", at = @At("RETURN"), cancellable = true)
    private void samsara$followOwner(CallbackInfoReturnable<EntityReference<LivingEntity>> cir) {
        List<UUID> all = samsara$allOwners();
        if (all.size() <= 1) {
            return;
        }
        TamableAnimal self = (TamableAnimal) (Object) this;
        LivingEntity chosen = PetFollow.select(self, all);
        if (chosen != null) {
            cir.setReturnValue(EntityReference.of(chosen));
        }
    }

    @Override
    public List<UUID> samsara$owners() {
        return new ArrayList<>(samsara$owners);
    }

    @Override
    public List<UUID> samsara$allOwners() {
        return PetOwners.normalize(samsara$primaryOwner(), samsara$owners);
    }

    @Override
    public PetOwners.AddResult samsara$addOwner(UUID uuid) {
        List<UUID> merged = samsara$allOwners();
        PetOwners.AddResult result = PetOwners.addOwner(merged, uuid,
                IdentityService.getInstance().maxOwners(),
                IdentityService.getInstance()::identityOf);
        if (result == PetOwners.AddResult.ADDED) {
            samsara$owners.clear();
            samsara$owners.addAll(merged);
        }
        return result;
    }

    @Override
    public void samsara$setOwners(List<UUID> owners) {
        samsara$owners.clear();
        if (owners != null) {
            for (UUID owner : owners) {
                if (owner != null && !samsara$owners.contains(owner)) {
                    samsara$owners.add(owner);
                }
            }
        }
    }

    @Unique
    private UUID samsara$primaryOwner() {
        TamableAnimal self = (TamableAnimal) (Object) this;
        Optional<EntityReference<LivingEntity>> reference =
                self.getEntityData().get(DATA_OWNERUUID_ID);
        return reference.map(EntityReference::getUUID).orElse(null);
    }
}
