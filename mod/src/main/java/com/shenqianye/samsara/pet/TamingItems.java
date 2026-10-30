package com.shenqianye.samsara.pet;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 物种 → 驯服物品映射（重复认主与跟随候选共用）。 */
public final class TamingItems {
    private TamingItems() {
    }

    public static boolean isTamingItem(Entity entity, ItemStack stack) {
        if (entity == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (entity instanceof Wolf) {
            return stack.is(Items.BONE);
        }
        if (entity instanceof Cat || entity instanceof Ocelot) {
            return stack.is(Items.COD) || stack.is(Items.SALMON);
        }
        if (entity instanceof Parrot) {
            return stack.is(ItemTags.PARROT_FOOD);
        }
        // 其他可驯服生物按原版食物扩展；没有更精确的通用“驯服物品”接口。
        return entity instanceof TamableAnimal animal && animal.isFood(stack);
    }

    public static boolean isHoldingTamingItem(Entity entity, Player player) {
        if (entity == null || player == null) {
            return false;
        }
        return isTamingItem(entity, player.getItemInHand(InteractionHand.MAIN_HAND))
                || isTamingItem(entity, player.getItemInHand(InteractionHand.OFF_HAND));
    }
}
