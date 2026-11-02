package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code .evt} 侧车的文件读写与 Minecraft 物品编解码（设计规范〈轨迹事件 .evt〉）。
 * <p>
 * 物品统一用 {@link ItemStack#CODEC} + {@link RegistryOps} + {@link NbtOps} 编码为长度前缀
 * NBT；解码只在主线程应用层调用，避免异步线程碰注册表。
 * <p>
 * 本类不参与保留扫描：只有 {@link TrackRecorder} 追加，回看/回放只读。
 */
public final class TrackEventStore {
    private TrackEventStore() {
    }

    /** 单文件读取结果。 */
    public record ReadResult(List<TrackEvents.Event> events, boolean truncated, String warning,
                             int validBytes) {
        public static ReadResult empty() {
            return new ReadResult(List.of(), false, null, 0);
        }

        public boolean hasEvents() {
            return !events.isEmpty();
        }
    }

    /** 某个槽位的非空物品（完整背包快照用）。 */
    public record InventoryItem(int slot, ItemStack stack) {
    }

    /** 完整背包快照。 */
    public record InventorySnapshot(List<InventoryItem> items) {
        public InventorySnapshot {
            items = List.copyOf(items);
        }

        public static InventorySnapshot empty() {
            return new InventorySnapshot(List.of());
        }
    }

    /** 装备快照：6 个可见槽 + 选中快捷栏。 */
    public record Equipment(ItemStack[] slots, int selectedSlot) {
        public Equipment {
            ItemStack[] copy = new ItemStack[TrackEvents.SLOT_COUNT];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = i < slots.length && slots[i] != null ? slots[i] : ItemStack.EMPTY;
            }
            slots = copy;
        }

        public static Equipment empty() {
            return new Equipment(new ItemStack[TrackEvents.SLOT_COUNT], 0);
        }

        public boolean hasAny() {
            for (ItemStack stack : slots) {
                if (!stack.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- 文件读写 ----

    public static ReadResult read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return ReadResult.empty();
        }
        try {
            TrackEvents.ReadResult parsed = TrackEvents.read(Files.readAllBytes(file));
            if (parsed.warning() != null) {
                Samsara.LOGGER.warn("[周目管理 samsara] 轨迹事件尾部不完整/无法解析，已读到最后一个完整事件: {}（{}）",
                        file, parsed.warning());
            }
            return new ReadResult(parsed.events(), parsed.truncated(), parsed.warning(),
                    parsed.validBytes());
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹事件读取失败 {}: {}", file, e.getMessage());
            return new ReadResult(List.of(), true, e.getMessage(), 0);
        }
    }

    /** 读取多个事件文件并按时间排序；缺失文件视为无事件。 */
    public static ReadResult readAll(List<Path> files) {
        List<TrackEvents.Event> all = new ArrayList<>();
        boolean truncated = false;
        String warning = null;
        if (files != null) {
            for (Path file : files) {
                ReadResult one = read(file);
                all.addAll(one.events());
                truncated |= one.truncated();
                if (warning == null) {
                    warning = one.warning();
                }
            }
        }
        all.sort(Comparator.comparingLong(TrackEvents.Event::timeMs));
        return new ReadResult(List.copyOf(all), truncated, warning, 0);
    }

    /** 整文件写回（合并同名事件文件时使用）。 */
    public static void writeFile(Path file, List<TrackEvents.Event> events) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(TrackEvents.header());
        long previous = 0;
        for (TrackEvents.Event event : events) {
            TrackEvents.write(bos, previous, event);
            previous = event.timeMs();
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bos.toByteArray());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 合并两个同名事件文件（旧布局迁移用）；保留两边全部记录。 */
    public static void mergeFile(Path src, Path dst) {
        try {
            List<TrackEvents.Event> merged = new ArrayList<>(read(dst).events());
            merged.addAll(read(src).events());
            merged.sort(Comparator.comparingLong(TrackEvents.Event::timeMs));
            writeFile(dst, merged);
            Files.deleteIfExists(src);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹事件文件合并失败 {}: {}", src, e.getMessage());
        }
    }

    public static void writeHeaderIfEmpty(java.io.OutputStream out, Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            return;
        }
        out.write(TrackEvents.header());
    }

    // ---- 事件 payload 编解码 ----

    public static byte[] encodeEquipment(ItemStack[] slots, int selectedSlot, RegistryAccess registries) {
        List<byte[]> encoded = new ArrayList<>(TrackEvents.SLOT_COUNT);
        for (int i = 0; i < TrackEvents.SLOT_COUNT; i++) {
            ItemStack stack = i < slots.length && slots[i] != null ? slots[i] : ItemStack.EMPTY;
            encoded.add(encodeItem(stack, registries));
        }
        return TrackEvents.equipPayload(selectedSlot, encoded);
    }

    public static Equipment decodeEquipment(byte[] payload, RegistryAccess registries) {
        TrackEvents.EquipPayload raw = TrackEvents.decodeEquipPayload(payload);
        ItemStack[] slots = new ItemStack[TrackEvents.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = decodeItem(raw.slots()[i], registries);
        }
        return new Equipment(slots, raw.selectedSlot());
    }

    public static byte[] encodeInventory(List<ItemStack> slots, RegistryAccess registries) {
        CompoundTag root = new CompoundTag();
        ListTag items = new ListTag();
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stack = slots.get(slot);
            byte[] nbt = encodeItem(stack, registries);
            if (nbt.length == 0) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("item", readTag(nbt));
            items.addAndUnwrap(entry);
        }
        root.put("items", items);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            NbtIo.writeAnyTag(root, out);
        } catch (IOException e) {
            throw new IllegalStateException("背包快照编码失败", e);
        }
        byte[] nbt = bos.toByteArray();
        ByteArrayOutputStream payload = new ByteArrayOutputStream(nbt.length + 5);
        TrackEvents.writeVarInt(payload, nbt.length);
        payload.write(nbt, 0, nbt.length);
        return payload.toByteArray();
    }

    public static InventorySnapshot decodeInventory(byte[] payload, RegistryAccess registries) {
        if (payload == null || payload.length == 0) {
            return InventorySnapshot.empty();
        }
        try {
            int[] pos = {0};
            int len = TrackEvents.readVarInt(payload, pos);
            if (len < 0 || pos[0] + len > payload.length) {
                return InventorySnapshot.empty();
            }
            byte[] nbt = new byte[len];
            System.arraycopy(payload, pos[0], nbt, 0, len);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            Tag tag = NbtIo.readAnyTag(in, NbtAccounter.unlimitedHeap());
            if (!(tag instanceof CompoundTag root)) {
                return InventorySnapshot.empty();
            }
            ListTag items = root.getListOrEmpty("items");
            List<InventoryItem> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompoundOrEmpty(i);
                ItemStack stack = decodeItemTag(entry.getCompoundOrEmpty("item"), registries);
                if (!stack.isEmpty()) {
                    out.add(new InventoryItem(entry.getIntOr("slot", i), stack));
                }
            }
            return new InventorySnapshot(out);
        } catch (IOException | RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 背包快照解码失败: {}", e.getMessage());
            return InventorySnapshot.empty();
        }
        } catch (IOException e) {
            return InventorySnapshot.empty();
        }
    }

    public static byte[] encodeItem(ItemStack stack, RegistryAccess registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return new byte[0];
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Tag tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            NbtIo.writeAnyTag(tag, out);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 装备物品编码失败: {}", e.getMessage());
            return new byte[0];
        }
        return bos.toByteArray();
    }

    public static ItemStack decodeItem(byte[] nbt, RegistryAccess registries) {
        if (nbt == null || nbt.length == 0 || registries == null) {
            return ItemStack.EMPTY;
        }
        try {
            return decodeItemTag(readTag(nbt), registries);
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 装备物品解码失败: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack decodeItemTag(Tag tag, RegistryAccess registries) {
        if (tag == null || registries == null) {
            return ItemStack.EMPTY;
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        var result = ItemStack.CODEC.parse(ops, tag);
        return result.result().orElse(ItemStack.EMPTY);
    }

    private static Tag readTag(byte[] nbt) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            return NbtIo.readAnyTag(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalArgumentException("NBT 读取失败", e);
        }
    }
}
