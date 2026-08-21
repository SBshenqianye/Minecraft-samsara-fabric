package com.shenqianye.samsara.geometry;

import java.util.Objects;

/**
 * 整数区块坐标（纯 Java，无 MC 依赖）。
 * 化线为面算法（§6.3）的基础数据类型。
 */
public final class ChunkCoord implements Comparable<ChunkCoord> {
    public final int x;
    public final int z;

    public ChunkCoord(int x, int z) {
        this.x = x;
        this.z = z;
    }

    /** 8 方向邻居 */
    public ChunkCoord north()     { return new ChunkCoord(x, z - 1); }
    public ChunkCoord south()     { return new ChunkCoord(x, z + 1); }
    public ChunkCoord east()      { return new ChunkCoord(x + 1, z); }
    public ChunkCoord west()      { return new ChunkCoord(x - 1, z); }
    public ChunkCoord northEast() { return new ChunkCoord(x + 1, z - 1); }
    public ChunkCoord northWest() { return new ChunkCoord(x - 1, z - 1); }
    public ChunkCoord southEast() { return new ChunkCoord(x + 1, z + 1); }
    public ChunkCoord southWest() { return new ChunkCoord(x - 1, z + 1); }

    /** 从方向索引（0=East, 1=NE, 2=N, ... 7=SE 逆时针）获取邻居 */
    public ChunkCoord neighbor(int dir) {
        return switch (dir % 8) {
            case 0 -> east();
            case 1 -> northEast();
            case 2 -> north();
            case 3 -> northWest();
            case 4 -> west();
            case 5 -> southWest();
            case 6 -> south();
            case 7 -> southEast();
            default -> throw new IllegalArgumentException("dir=" + dir);
        };
    }

    /** 两点之间的方向索引（0=East, 1=NE, ... 逆时针），要求是 8 邻域 */
    public int directionIndexTo(ChunkCoord other) {
        int dx = Integer.signum(other.x - x);
        int dz = Integer.signum(other.z - z);
        // dx + 3*dz 唯一映射 8 方向
        return switch (dx + 3 * dz) {
            case 1  -> 0;  // East    (+1, 0)
            case -2 -> 1;  // NE      (+1, -1)
            case -3 -> 2;  // North   (0, -1)
            case -4 -> 3;  // NW      (-1, -1)
            case -1 -> 4;  // West    (-1, 0)
            case 2  -> 5;  // SW      (-1, +1)
            case 3  -> 6;  // South   (0, +1)
            case 4  -> 7;  // SE      (+1, +1)
            default -> throw new IllegalArgumentException(
                    "Not 8-neighbor: " + this + " -> " + other);
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkCoord c)) return false;
        return x == c.x && z == c.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }

    @Override
    public int compareTo(ChunkCoord o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + z + ")";
    }
}
