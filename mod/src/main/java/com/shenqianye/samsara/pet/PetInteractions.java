package com.shenqianye.samsara.pet;

import com.shenqianye.samsara.command.ChatFormat;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.TamableAnimal;

/**
 * 重复认主（方案 A）：任何玩家用对应驯服物品右键已驯服宠物，把自己加入 owner。
 * 同一身份组按 data UUID 去重；达到 {@code pets.maxOwners} 时拒绝并提示。
 */
public final class PetInteractions {
    private PetInteractions() {
    }

    public static void init() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(entity instanceof TamableAnimal pet) || !pet.isTame()) {
                return InteractionResult.PASS;
            }
            if (!(pet instanceof TamableAnimalOwners owners)) {
                return InteractionResult.PASS;
            }
            if (!TamingItems.isTamingItem(pet, serverPlayer.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            PetOwners.AddResult result = owners.samsara$addOwner(serverPlayer.getUUID());
            return switch (result) {
                case ADDED -> {
                    serverPlayer.sendSystemMessage(ChatFormat.formatComponent(
                            "已加入这只宠物的主人列表（共 " + owners.samsara$allOwners().size()
                                    + " 位，上限 " + com.shenqianye.samsara.identity.IdentityService
                                    .getInstance().maxOwners() + "）"));
                    pet.setPersistenceRequired();
                    yield InteractionResult.SUCCESS;
                }
                case ALREADY_OWNER -> InteractionResult.PASS;
                case LIMIT_REACHED -> {
                    serverPlayer.sendSystemMessage(ChatFormat.formatComponent(
                            "这只宠物已达到主人上限（" + com.shenqianye.samsara.identity.IdentityService
                                    .getInstance().maxOwners() + "），无法继续认主"));
                    yield InteractionResult.FAIL;
                }
            };
        });
    }
}
