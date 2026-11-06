package com.shenqianye.samsara.container;

import com.shenqianye.samsara.lottery.LotteryItemCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflinePlayerInventoryServiceTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 单测 JVM 里 Items 类在 bootstrap 之后才加载，holder 的默认组件没有绑定；
        // 手动绑定，模拟真实服务器 registryAccess 下的 ItemStack 行为。
        for (net.minecraft.world.item.Item item
                : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item.builtInRegistryHolder()
                    instanceof net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item> ref
                    && !ref.areComponentsBound()) {
                ref.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
            }
        }
        registries = RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    @Test
    void parsesInventoryAndEnderItemsNbt() {
        ListTag inventory = new ListTag();
        inventory.add(stack(new ItemStack(Items.DIAMOND, 3), (byte) 5));
        ListTag ender = new ListTag();
        ender.add(stack(new ItemStack(Items.EMERALD, 2), (byte) 1));

        Set<String> items = OfflinePlayerInventoryService.parseItems(inventory, registries);
        assertFalse(items.isEmpty());
        ItemStack decoded = LotteryItemCodec.decode(items.iterator().next(), registries);
        assertEquals(Items.DIAMOND, decoded.getItem());
        assertEquals(3, decoded.getCount());

        Set<String> enderItems = OfflinePlayerInventoryService.parseItems(ender, registries);
        ItemStack enderStack = LotteryItemCodec.decode(enderItems.iterator().next(), registries);
        assertEquals(Items.EMERALD, enderStack.getItem());
        assertEquals(2, enderStack.getCount());
    }

    @Test
    void ignoresEmptyAndUnknownEntries() {
        ListTag list = new ListTag();
        CompoundTag empty = new CompoundTag();
        list.add(empty);
        assertTrue(OfflinePlayerInventoryService.parseItems(list, registries).isEmpty());
        assertTrue(OfflinePlayerInventoryService.parseItems(null, registries).isEmpty());
    }

    private static CompoundTag stack(ItemStack stack, byte slot) {
        var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,
                registries);
        CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        tag.putByte("Slot", slot);
        return tag;
    }
}
