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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryServiceTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
    void scopeSizesAndAliases() {
        assertEquals(41, PlayerInventoryService.SlotSpec.INVENTORY.size);
        assertEquals(27, PlayerInventoryService.SlotSpec.ENDER.size);
        assertEquals(4, PlayerInventoryService.SlotSpec.ARMOR.size);
        assertEquals(36, PlayerInventoryService.SlotSpec.ARMOR.base);
        assertEquals(PlayerInventoryService.SlotSpec.INVENTORY,
                PlayerInventoryService.SlotSpec.from("inv"));
        assertEquals(PlayerInventoryService.SlotSpec.ENDER,
                PlayerInventoryService.SlotSpec.from("ender_chest"));
        assertNull(PlayerInventoryService.SlotSpec.from("cursor"));
    }

    @Test
    void revisionChangesWhenSlotsChange() {
        List<String> first = new ArrayList<>(List.of("a", "", "b"));
        List<String> second = new ArrayList<>(List.of("a", "c", "b"));
        long revision = PlayerInventoryService.revisionOf(first);
        assertEquals(revision, PlayerInventoryService.revisionOf(new ArrayList<>(first)));
        assertNotEquals(revision, PlayerInventoryService.revisionOf(second));
        assertTrue(revision >= 0);
    }

    @Test
    void validatesSlotCountAndStackSize() {
        List<String> shortList = new ArrayList<>();
        for (int i = 0; i < 40; i++) shortList.add("");
        assertNotNull(PlayerInventoryService.validateEncodedSlots(
                shortList, PlayerInventoryService.SlotSpec.INVENTORY, registries));

        List<String> full = new ArrayList<>();
        for (int i = 0; i < 41; i++) full.add("");
        full.set(0, LotteryItemCodec.encode(new ItemStack(Items.DIAMOND, 1), registries));
        assertNull(PlayerInventoryService.validateEncodedSlots(
                full, PlayerInventoryService.SlotSpec.INVENTORY, registries));

        full.set(1, "not-a-valid-base64");
        assertNotNull(PlayerInventoryService.validateEncodedSlots(
                full, PlayerInventoryService.SlotSpec.INVENTORY, registries));
    }

    @Test
    void decodesOfflineInventoryAndEnderItems() {
        CompoundTag tag = new CompoundTag();
        ListTag inventory = new ListTag();
        inventory.add(stack(new ItemStack(Items.DIAMOND, 3), (byte) 5));
        tag.put("Inventory", inventory);
        ListTag ender = new ListTag();
        ender.add(stack(new ItemStack(Items.EMERALD, 2), (byte) 1));
        tag.put("EnderItems", ender);

        List<String> slots = PlayerInventoryService.decodeOfflineSlots(
                tag, PlayerInventoryService.SlotSpec.INVENTORY, registries);
        assertEquals(41, slots.size());
        assertEquals("", slots.get(0));
        ItemStack diamond = LotteryItemCodec.decode(slots.get(5), registries);
        assertEquals(Items.DIAMOND, diamond.getItem());
        assertEquals(3, diamond.getCount());

        List<String> enderSlots = PlayerInventoryService.decodeOfflineSlots(
                tag, PlayerInventoryService.SlotSpec.ENDER, registries);
        assertEquals(27, enderSlots.size());
        assertEquals(Items.EMERALD,
                LotteryItemCodec.decode(enderSlots.get(1), registries).getItem());
    }

    @Test
    void pendingQueueRoundTripAndCorruption() throws Exception {
        Path dir = Files.createTempDirectory("samsara-inventory-test");
        Path queue = dir.resolve("player-inventory.json");
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 41; i++) slots.add("");
        slots.set(0, LotteryItemCodec.encode(new ItemStack(Items.GOLD_INGOT, 7), registries));
        String json;
        StringBuilder builder = new StringBuilder("{\"slots\":[");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append('"').append(slots.get(i)).append('"');
        }
        builder.append("],\"revision\":1}");
        json = builder.toString();
        assertTrue(PlayerInventoryService.writeQueueFile(queue, json));
        List<String> read = PlayerInventoryService.readQueueFile(
                queue, PlayerInventoryService.SlotSpec.INVENTORY);
        assertNotNull(read);
        assertEquals(41, read.size());
        assertEquals(Items.GOLD_INGOT,
                LotteryItemCodec.decode(read.get(0), registries).getItem());

        Files.writeString(queue, "not-json");
        assertNull(PlayerInventoryService.readQueueFile(
                queue, PlayerInventoryService.SlotSpec.INVENTORY));

        Files.deleteIfExists(queue);
        Files.deleteIfExists(dir);
    }

    @Test
    void emptyQueueDirectoryIsSafe() {
        assertNull(PlayerInventoryService.readQueueFile(
                Path.of("definitely-missing-inventory-queue.json"),
                PlayerInventoryService.SlotSpec.INVENTORY));
        assertFalse(PlayerInventoryService.writeQueueFile(null, "{}"));
    }

    private static CompoundTag stack(ItemStack stack, byte slot) {
        var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,
                registries);
        CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        tag.putByte("Slot", slot);
        return tag;
    }
}
