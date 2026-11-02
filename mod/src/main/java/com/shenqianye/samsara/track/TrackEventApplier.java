package com.shenqianye.samsara.track;

/**
 * 把 {@link TrackEventCursor} 的时间状态应用到人偶（设计文档〈轨迹事件〉）。
 * <p>
 * 前向播放逐条应用跨越的事件；跳转/倒放只按快照重建装备、飞行模式与载具，
 * 并清空挥动/使用动作，避免“倒着拉弓”的残留。
 */
public final class TrackEventApplier {
    private TrackEventApplier() {
    }

    /** 跳到/倒放到某个时刻：清空动作，再按最近快照重建可见状态。 */
    public static void resetTo(TrackMannequin mannequin, TrackEventCursor cursor, long timeMs) {
        if (mannequin == null || cursor == null) {
            return;
        }
        mannequin.resetActionState();
        applySnapshot(mannequin, cursor, timeMs);
    }

    /** 只按时间快照重建装备/飞行/载具，不触碰动作。 */
    public static void applySnapshot(TrackMannequin mannequin, TrackEventCursor cursor, long timeMs) {
        if (mannequin == null || cursor == null) {
            return;
        }
        TrackEventStore.Equipment equipment = cursor.equipmentAt(timeMs);
        mannequin.applyEquipment(equipment.slots(), equipment.selectedSlot());
        int flight = cursor.flightModeAt(timeMs);
        if (flight >= 0) {
            mannequin.applyFlightMode(flight);
        }
        String vehicle = cursor.vehicleAt(timeMs);
        if (vehicle != null) {
            mannequin.applyVehicleType(vehicle);
        }
    }

    /** 正放推进：逐条应用 {@code (from, to]} 内的事件。 */
    public static void advance(TrackMannequin mannequin, TrackEventCursor cursor,
                               long fromExclusive, long toInclusive) {
        if (mannequin == null || cursor == null || toInclusive <= fromExclusive) {
            return;
        }
        for (TrackEvents.Event event : cursor.between(fromExclusive, toInclusive)) {
            switch (event.type()) {
                case TrackEvents.TYPE_EQUIP -> {
                    TrackEventStore.Equipment equipment = cursor.equipment(event);
                    mannequin.applyEquipment(equipment.slots(), equipment.selectedSlot());
                }
                case TrackEvents.TYPE_FLIGHT_MODE ->
                        mannequin.applyFlightMode(TrackEvents.flightMode(event.payload()));
                case TrackEvents.TYPE_VEHICLE ->
                        mannequin.applyVehicleType(TrackEvents.vehicle(event.payload()));
                case TrackEvents.TYPE_SWING -> mannequin.applySwing(
                        TrackEvents.hand(event.payload()), TrackEvents.animation(event.payload()));
                case TrackEvents.TYPE_USE_START ->
                        mannequin.applyUseStart(TrackEvents.hand(event.payload()));
                case TrackEvents.TYPE_USE_STOP -> mannequin.applyUseStop();
                default -> {
                    // INVENTORY / BLOCK_BREAK 不影响人偶外观
                }
            }
        }
    }
}
