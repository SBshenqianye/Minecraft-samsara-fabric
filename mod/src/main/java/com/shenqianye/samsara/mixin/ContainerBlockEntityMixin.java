package com.shenqianye.samsara.mixin;

import com.shenqianye.samsara.container.ContainerIndexService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 容器索引的最小侵入钩子：只挂 {@link BaseContainerBlockEntity} 的四个写入口，
 * 不 hook 所有方块实体。每次变化只标记该容器 dirty，由索引服务在下一 tick 刷新。
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerBlockEntityMixin {
    @Inject(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void samsara$markSetItem(int slot, ItemStack stack, CallbackInfo ci) {
        ContainerIndexService.getInstance().markDirty((BaseContainerBlockEntity) (Object) this);
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void samsara$markRemoveItem(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        ContainerIndexService.getInstance().markDirty((BaseContainerBlockEntity) (Object) this);
    }

    @Inject(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void samsara$markRemoveItemNoUpdate(int slot, CallbackInfoReturnable<ItemStack> cir) {
        ContainerIndexService.getInstance().markDirty((BaseContainerBlockEntity) (Object) this);
    }

    @Inject(method = "clearContent()V", at = @At("HEAD"))
    private void samsara$markClearContent(CallbackInfo ci) {
        ContainerIndexService.getInstance().markDirty((BaseContainerBlockEntity) (Object) this);
    }
}
