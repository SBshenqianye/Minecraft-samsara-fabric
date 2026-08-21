package com.shenqianye.chunkmanager.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkCoordinate extends Point{
    private int X;
    private int Z;

    public String toString(){
        return "(" + X + ", " + Z + ")";
    }

    public static boolean equalsBetween(ChunkCoordinate coordinateA, ChunkCoordinate coordinateB){
        if(coordinateA.X!=coordinateB.X){
            return false;
        }
        if(coordinateA.Z!=coordinateB.Z){
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkCoordinate that = (ChunkCoordinate) o;
        if (X != that.X) return false;
        if (Z != that.Z) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(X,Z);
    }

    public ChunkCoordinate getSouth(int extend){
        return new ChunkCoordinate(X, Z + extend);
    }
    public ChunkCoordinate getNorth(int extend){
        return new ChunkCoordinate(X, Z - extend);
    }
    public ChunkCoordinate getEast(int extend){
        return new ChunkCoordinate(X + extend, Z);
    }
    public ChunkCoordinate getWest(int extend){
        return new ChunkCoordinate(X - extend, Z);
    }
    public ChunkCoordinate getSouthEast(int extend){
        return new ChunkCoordinate(X + extend, Z + extend);
    }
    public ChunkCoordinate getNorthEast(int extend){
        return new ChunkCoordinate(X + extend, Z - extend);
    }
    public ChunkCoordinate getSouthWest(int extend){
        return new ChunkCoordinate(X - extend, Z + extend);
    }
    public ChunkCoordinate getNorthWest(int extend){
        return new ChunkCoordinate(X - extend, Z - extend);
    }
}
