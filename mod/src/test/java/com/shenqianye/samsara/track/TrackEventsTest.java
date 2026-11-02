package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code .evt} 纯格式往返、尾部截断与时间查询（不启动 MC）。 */
class TrackEventsTest {
    @TempDir
    Path tmp;

    @Test
    void roundTripAllEventTypes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TrackEvents.header());
        byte[] equip = TrackEvents.equipPayload(4, List.of(new byte[]{1, 2, 3}, new byte[0]));
        TrackEvents.Event[] events = {
                new TrackEvents.Event(1000, TrackEvents.TYPE_EQUIP, equip),
                new TrackEvents.Event(1100, TrackEvents.TYPE_SWING, TrackEvents.swingPayload(0, 1)),
                new TrackEvents.Event(1200, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(1)),
                new TrackEvents.Event(1300, TrackEvents.TYPE_USE_STOP, new byte[0]),
                new TrackEvents.Event(1400, TrackEvents.TYPE_FLIGHT_MODE,
                        TrackEvents.flightPayload(TrackEvents.FLIGHT_CREATIVE)),
                new TrackEvents.Event(1500, TrackEvents.TYPE_VEHICLE,
                        TrackEvents.vehiclePayload("minecraft:oak_boat"))
        };
        long previous = 0;
        for (TrackEvents.Event event : events) {
            TrackEvents.write(out, previous, event);
            previous = event.timeMs();
        }
        Path file = tmp.resolve("a.evt");
        Files.write(file, out.toByteArray());

        TrackEvents.ReadResult result = TrackEvents.read(Files.readAllBytes(file));
        assertFalse(result.truncated());
        assertEquals(6, result.events().size());
        assertEquals(TrackEvents.TYPE_EQUIP, result.events().get(0).type());
        assertEquals(1000, result.events().get(0).timeMs());
        assertEquals(1500, result.events().get(5).timeMs());
        assertArrayEquals(TrackEvents.swingPayload(0, 1), result.events().get(1).payload());
        assertEquals(TrackEvents.FLIGHT_CREATIVE,
                TrackEvents.flightMode(result.events().get(4).payload()));
        assertEquals("minecraft:oak_boat",
                TrackEvents.vehicle(result.events().get(5).payload()));
        TrackEvents.EquipPayload decoded = TrackEvents.decodeEquipPayload(equip);
        assertEquals(4, decoded.selectedSlot());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.slots()[0]);
        assertEquals(null, decoded.slots()[1]);
    }

    @Test
    void truncatedTailKeepsLastCompleteEvent() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TrackEvents.header());
        TrackEvents.write(out, 0, new TrackEvents.Event(100, TrackEvents.TYPE_EQUIP,
                TrackEvents.equipPayload(0, List.of())));
        TrackEvents.write(out, 100, new TrackEvents.Event(200, TrackEvents.TYPE_SWING,
                TrackEvents.swingPayload(0, 0)));
        byte[] complete = out.toByteArray();
        byte[] truncated = new byte[complete.length + 2];
        System.arraycopy(complete, 0, truncated, 0, complete.length);
        truncated[complete.length] = (byte) 0x80; // 不完整 varint
        truncated[complete.length + 1] = 0;

        TrackEvents.ReadResult result = TrackEvents.read(truncated);
        assertTrue(result.truncated());
        assertEquals(2, result.events().size());
        assertEquals(TrackEvents.TYPE_SWING, result.events().get(1).type());
    }

    @Test
    void equipmentAtUsesNearestSnapshotAndDefaultsToEmpty() {
        byte[] empty0 = TrackEvents.equipPayload(0, List.of());
        byte[] empty4 = TrackEvents.equipPayload(4, List.of());
        List<TrackEvents.Event> events = List.of(
                new TrackEvents.Event(100, TrackEvents.TYPE_EQUIP, empty0),
                new TrackEvents.Event(200, TrackEvents.TYPE_EQUIP, empty4));
        TrackEventCursor cursor = new TrackEventCursor(events, null);
        assertTrue(cursor.hasEquipment());
        assertEquals(0, cursor.equipmentAt(99).selectedSlot());
        assertEquals(0, cursor.equipmentAt(100).selectedSlot());
        assertEquals(0, cursor.equipmentAt(199).selectedSlot());
        assertEquals(4, cursor.equipmentAt(200).selectedSlot());
        assertEquals(4, cursor.equipmentAt(999).selectedSlot());
    }

    @Test
    void oldTrackWithoutEventsIsEmpty() {
        TrackEventCursor cursor = new TrackEventCursor(List.of(), null);
        assertFalse(cursor.hasEquipment());
        assertTrue(cursor.equipmentAt(0).slots()[0].isEmpty());
        assertEquals(-1, cursor.flightModeAt(0));
        assertEquals(null, cursor.vehicleAt(0));
        assertTrue(cursor.between(0, 1000).isEmpty());
    }

    @Test
    void betweenIsForwardExclusiveInclusive() {
        List<TrackEvents.Event> events = List.of(
                new TrackEvents.Event(100, TrackEvents.TYPE_SWING, TrackEvents.swingPayload(0, 0)),
                new TrackEvents.Event(200, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(0)),
                new TrackEvents.Event(300, TrackEvents.TYPE_USE_STOP, new byte[0]));
        TrackEventCursor cursor = new TrackEventCursor(events, null);
        assertEquals(1, cursor.between(100, 200).size());
        assertEquals(2, cursor.between(100, 300).size());
        assertEquals(0, cursor.between(300, 400).size());
    }
}
