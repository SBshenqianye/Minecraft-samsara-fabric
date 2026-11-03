package com.shenqianye.samsara.track;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code .evt} 的只读时间查询层（设计文档〈轨迹事件〉）。
 * <p>
 * 回放与回看共享本类：{@link #equipmentAt(long)}、{@link #flightModeAt(long)}、
 * {@link #vehicleAt(long)} 都取“时间 ≤ t 的最近快照”；{@link #between(long, long)}
 * 用于前向播放跨越动作事件。解码延迟到调用时，且只在主线程使用。
 */
public final class TrackEventCursor {
    private final List<TrackEvents.Event> events;
    private final RegistryAccess registries;

    /** 最近一次装备解码缓存（装备事件很少，避免每 tick 解 NBT）。 */
    private int cachedEquipIndex = -1;
    private TrackEventStore.Equipment cachedEquip;

    public TrackEventCursor(List<TrackEvents.Event> events, RegistryAccess registries) {
        this.events = events == null ? List.of() : List.copyOf(events);
        this.registries = registries;
    }

    public static TrackEventCursor empty() {
        return new TrackEventCursor(List.of(), null);
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public int size() {
        return events.size();
    }

    public List<TrackEvents.Event> events() {
        return events;
    }

    /** 是否存在任何 EQUIP 事件；无事件/旧轨迹为 false。 */
    public boolean hasEquipment() {
        for (TrackEvents.Event event : events) {
            if (event.type() == TrackEvents.TYPE_EQUIP) {
                return true;
            }
        }
        return false;
    }

    /** 时间 ≤ t 的最近 EQUIP；没有则空手（旧轨迹降级）。 */
    public TrackEventStore.Equipment equipmentAt(long timeMs) {
        int index = lastIndex(TrackEvents.TYPE_EQUIP, timeMs);
        if (index < 0) {
            return TrackEventStore.Equipment.empty();
        }
        if (index == cachedEquipIndex && cachedEquip != null) {
            return cachedEquip;
        }
        cachedEquip = TrackEventStore.decodeEquipment(events.get(index).payload(), registries);
        cachedEquipIndex = index;
        return cachedEquip;
    }

    /** 时间 ≤ t 的最近 FLIGHT_MODE；无事件返回 -1（未知，旧轨迹沿用 flags）。 */
    public int flightModeAt(long timeMs) {
        int index = lastIndex(TrackEvents.TYPE_FLIGHT_MODE, timeMs);
        return index < 0 ? -1 : TrackEvents.flightMode(events.get(index).payload());
    }

    /** 时间 ≤ t 的最近 VEHICLE；无事件返回 null；空串表示无载具。 */
    public TrackEvents.VehicleInfo vehicleInfoAt(long timeMs) {
        int index = lastIndex(TrackEvents.TYPE_VEHICLE, timeMs);
        return index < 0 ? null : TrackEvents.vehicleInfo(events.get(index).payload());
    }

    /** 时间 ≤ t 的最近 VEHICLE 实体类型 id；无事件返回 null；空串表示无载具。 */
    public String vehicleAt(long timeMs) {
        TrackEvents.VehicleInfo info = vehicleInfoAt(timeMs);
        return info == null ? null : info.typeId();
    }

    /** 时间 ≤ t 的最近完整背包；无事件返回 null（表示该段没有记录背包）。 */
    public TrackEventStore.InventorySnapshot inventoryAt(long timeMs) {
        int index = lastIndex(TrackEvents.TYPE_INVENTORY, timeMs);
        if (index < 0) {
            return null;
        }
        return TrackEventStore.decodeInventory(events.get(index).payload(), registries);
    }

    /** 前向播放跨越的事件：{@code fromExclusive < time <= toInclusive}，按时间升序。 */
    public List<TrackEvents.Event> between(long fromExclusive, long toInclusive) {
        if (toInclusive <= fromExclusive || events.isEmpty()) {
            return List.of();
        }
        int start = firstIndexAfter(fromExclusive);
        List<TrackEvents.Event> out = new ArrayList<>();
        for (int i = start; i < events.size(); i++) {
            TrackEvents.Event event = events.get(i);
            if (event.timeMs() > toInclusive) {
                break;
            }
            out.add(event);
        }
        return out;
    }

    /** 反序列化一条事件（应用层用）。 */
    public TrackEventStore.Equipment equipment(TrackEvents.Event event) {
        return TrackEventStore.decodeEquipment(event.payload(), registries);
    }

    private int lastIndex(int type, long timeMs) {
        if (events.isEmpty()) {
            return -1;
        }
        int lo = 0;
        int hi = events.size() - 1;
        int floor = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).timeMs() <= timeMs) {
                floor = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        for (int i = floor; i >= 0; i--) {
            if (events.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    private int firstIndexAfter(long timeMs) {
        int lo = 0;
        int hi = events.size() - 1;
        int ans = events.size();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).timeMs() > timeMs) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    /** 测试/调试用：某个时间点的人偶可读状态摘要。 */
    public String describeAt(long timeMs) {
        TrackEventStore.Equipment equipment = equipmentAt(timeMs);
        int nonEmpty = 0;
        for (ItemStack stack : equipment.slots()) {
            if (!stack.isEmpty()) {
                nonEmpty++;
            }
        }
        int flight = flightModeAt(timeMs);
        String vehicle = vehicleAt(timeMs);
        return "装备 " + nonEmpty + " 件 · 选中 " + equipment.selectedSlot()
                + " · 飞行 " + flightName(flight)
                + " · 载具 " + (vehicle == null || vehicle.isEmpty() ? "无" : vehicle);
    }

    public static String flightName(int mode) {
        return switch (mode) {
            case TrackEvents.FLIGHT_ELYTRA -> "elytra";
            case TrackEvents.FLIGHT_CREATIVE -> "creative";
            case TrackEvents.FLIGHT_NONE -> "none";
            default -> "unknown";
        };
    }
}
