package com.shenqianye.samsara.lottery;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * ItemStack ↔ Base64(NBT) 编解码。
 *
 * <p>复用 {@link ItemStack#CODEC} + {@link RegistryOps}，完整保留附魔、名字、耐久与
 * 数据组件；只用于服务端持久化，不写进配置 YAML。
 */
public final class LotteryItemCodec {
    /**
     * 原版 {@link ItemStack#CODEC} 的 count 只接受 1–99。基金/仓库允许无限堆叠，
     * 因此超过 99 的堆叠会额外包一层：内层物品固定 count=1，外层记录真实数量。
     * 普通堆叠仍写原版结构，旧数据无需迁移。
     */
    private static final String WRAPPER_ITEM = "samsara:item";
    private static final String WRAPPER_COUNT = "samsara:count";
    private static final int VANILLA_CODEC_MAX_COUNT = 99;

    private LotteryItemCodec() {
    }

    public static String encode(ItemStack stack, RegistryAccess registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return "";
        }
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            Tag tag = encodeTag(stack, ops);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                NbtIo.writeAnyTag(tag, out);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (RuntimeException | IOException e) {
            return "";
        }
    }

    private static Tag encodeTag(ItemStack stack, RegistryOps<Tag> ops) {
        if (stack.getCount() <= VANILLA_CODEC_MAX_COUNT) {
            return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        }
        ItemStack single = stack.copy();
        int count = single.getCount();
        single.setCount(1);
        Tag inner = ItemStack.CODEC.encodeStart(ops, single).getOrThrow();
        CompoundTag wrapper = new CompoundTag();
        wrapper.put(WRAPPER_ITEM, inner);
        wrapper.putInt(WRAPPER_COUNT, count);
        return wrapper;
    }

    public static ItemStack decode(String encoded, RegistryAccess registries) {
        if (encoded == null || encoded.isBlank() || registries == null) {
            return ItemStack.EMPTY;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            Tag tag;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                tag = NbtIo.readAnyTag(in, NbtAccounter.unlimitedHeap());
            }
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            if (tag instanceof CompoundTag compound
                    && !compound.contains("id")
                    && compound.contains(WRAPPER_ITEM)
                    && compound.contains(WRAPPER_COUNT)) {
                ItemStack stack = ItemStack.CODEC.parse(ops, compound.get(WRAPPER_ITEM))
                        .result().orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    stack.setCount(Math.max(1, compound.getIntOr(WRAPPER_COUNT, stack.getCount())));
                }
                return stack;
            }
            return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (RuntimeException | IOException e) {
            return ItemStack.EMPTY;
        }
    }

    public static List<String> encodeAll(List<ItemStack> stacks, RegistryAccess registries) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : stacks) {
            String encoded = encode(stack, registries);
            if (!encoded.isBlank()) {
                out.add(encoded);
            }
        }
        return out;
    }

    public static List<ItemStack> decodeAll(List<String> encoded, RegistryAccess registries) {
        List<ItemStack> out = new ArrayList<>();
        if (encoded == null) {
            return out;
        }
        for (String value : encoded) {
            ItemStack stack = decode(value, registries);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }
}
