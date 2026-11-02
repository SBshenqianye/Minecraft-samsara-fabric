package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .evt} 文件往返与装备/背包 payload 结构。
 * <p>物品 NBT 的真实 ItemStack 解码在测试服集成验证；单元测试不启动 Minecraft
 * 注册表，避免把 MC bootstrap 带入纯逻辑测试。
 */
class TrackEventStoreTest {
    @TempDir
    Path tmp;

    @Test
    void equipmentPayloadPreservesSlotsAndSelectedSlot() {
        byte[] payload = TrackEvents.equipPayload(6, List.of(
                new byte[]{10, 20, 30},
                new byte[0],
                new byte[]{1},
                new byte[]{2},
                new byte[0],
                new byte[]{3}));
        TrackEvents.EquipPayload decoded = TrackEvents.decodeEquipPayload(payload);
        assertEquals(6, decoded.selectedSlot());
        assertArrayEquals(new byte[]{10, 20, 30}, decoded.slots()[0]);
        assertEquals(null, decoded.slots()[1]);
        assertArrayEquals(new byte[]{1}, decoded.slots()[2]);
        assertArrayEquals(new byte[]{3}, decoded.slots()[5]);

        // 全空槽 + 选中槽
        TrackEvents.EquipPayload empty = TrackEvents.decodeEquipPayload(
                TrackEvents.equipPayload(4, List.of()));
        assertEquals(4, empty.selectedSlot());
        for (byte[] slot : empty.slots()) {
            assertEquals(null, slot);
        }
    }

    @Test
    void inventorySnapshotWithNoItemsIsSafe() {
        byte[] payload = TrackEventStore.encodeInventory(List.of(), null);
        TrackEventStore.InventorySnapshot snapshot = TrackEventStore.decodeInventory(payload, null);
        assertTrue(snapshot.items().isEmpty());
        assertTrue(TrackEventStore.decodeInventory(new byte[0], null).items().isEmpty());
    }

    @Test
    void eventFileRoundTripAndTruncatedTail() throws Exception {
        byte[] equip = TrackEvents.equipPayload(1, List.of(new byte[]{42}));
        Path file = tmp.resolve("2026-11-02").resolve("u.evt");
        Files.createDirectories(file.getParent());
        var out = new java.io.ByteArrayOutputStream();
        out.write(TrackEvents.header());
        TrackEvents.write(out, 0, new TrackEvents.Event(500, TrackEvents.TYPE_EQUIP, equip));
        TrackEvents.write(out, 500, new TrackEvents.Event(700, TrackEvents.TYPE_USE_START,
                TrackEvents.useStartPayload(0)));
        Files.write(file, out.toByteArray());

        TrackEventStore.ReadResult result = TrackEventStore.read(file);
        assertFalse(result.truncated());
        assertEquals(2, result.events().size());
        assertEquals(500, result.events().get(0).timeMs());
        TrackEvents.EquipPayload decoded = TrackEvents.decodeEquipPayload(
                result.events().get(0).payload());
        assertEquals(1, decoded.selectedSlot());
        assertArrayEquals(new byte[]{42}, decoded.slots()[0]);

        byte[] bytes = Files.readAllBytes(file);
        byte[] truncated = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, truncated, 0, bytes.length);
        truncated[bytes.length] = (byte) 0x80;
        Files.write(file, truncated);
        TrackEventStore.ReadResult cut = TrackEventStore.read(file);
        assertTrue(cut.truncated());
        assertEquals(2, cut.events().size());
    }

    @Test
    void writeFilePreservesAbsoluteAndRelativeTimes() throws Exception {
        Path file = tmp.resolve("m.evt");
        List<TrackEvents.Event> events = new ArrayList<>();
        events.add(new TrackEvents.Event(1_700_000_000_000L, TrackEvents.TYPE_EQUIP,
                TrackEvents.equipPayload(0, List.of())));
        events.add(new TrackEvents.Event(1_700_000_000_250L, TrackEvents.TYPE_SWING,
                TrackEvents.swingPayload(1, 2)));
        TrackEventStore.writeFile(file, events);

        TrackEventStore.ReadResult result = TrackEventStore.read(file);
        assertEquals(2, result.events().size());
        assertEquals(1_700_000_000_000L, result.events().get(0).timeMs());
        assertEquals(1_700_000_000_250L, result.events().get(1).timeMs());
    }

    @Test
    void missingEventFileIsEmptyNotAnError() {
        TrackEventStore.ReadResult result = TrackEventStore.read(tmp.resolve("nope.evt"));
        assertTrue(result.events().isEmpty());
        assertFalse(result.truncated());
    }
}
