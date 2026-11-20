package com.shenqianye.samsara.container;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器索引条目：维度 + 方块坐标 + 容器类型 + 非空槽位物品编码。
 *
 * <p>物品编码复用 {@code LotteryItemCodec}，完整保留附魔、名字、耐久与数据组件。
 * 空槽位用空字符串占位，保持槽位序号不变；{@code slots} 只在读取接口返回，
 * 列表接口只返回槽位数与非空数，避免一次传输大量 NBT。
 */
public final class ContainerEntry {
    public String dimension = "";
    public int x;
    public int y;
    public int z;
    public String type = "";
    public int size;
    public int nonEmpty;
    /** 大箱子朝向：x=横向（沿 X 轴并排），z=纵向（沿 Z 轴并排）。 */
    public String axis = "";
    public long revision = 1;
    public long updatedAt;
    public List<String> slots = new ArrayList<>();

    public boolean empty() {
        return nonEmpty <= 0;
    }

    public boolean matches(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        for (String slot : slots) {
            if (encoded.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    public ContainerViews.Entry view(boolean includeSlots, String source) {
        return new ContainerViews.Entry(dimension, x, y, z, type, size, nonEmpty,
                axis == null ? "" : axis, empty(), revision, updatedAt,
                source == null ? "cache" : source,
                includeSlots ? new ArrayList<>(slots) : null);
    }

    public ContainerEntry copy() {
        ContainerEntry copy = new ContainerEntry();
        copy.dimension = dimension;
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.type = type;
        copy.size = size;
        copy.nonEmpty = nonEmpty;
        copy.axis = axis;
        copy.revision = revision;
        copy.updatedAt = updatedAt;
        copy.slots = new ArrayList<>(slots);
        return copy;
    }

    /** 仅比较内容，用于判断刷新是否需要提升 revision。 */
    public boolean sameContent(String otherType, List<String> otherSlots) {
        return type.equals(otherType) && slots.equals(otherSlots);
    }
}
