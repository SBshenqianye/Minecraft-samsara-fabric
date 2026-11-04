package com.shenqianye.samsara.lottery;

import net.minecraft.core.RegistryAccess;
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
    private LotteryItemCodec() {
    }

    public static String encode(ItemStack stack, RegistryAccess registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return "";
        }
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            Tag tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                NbtIo.writeAnyTag(tag, out);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (RuntimeException | IOException e) {
            return "";
        }
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
