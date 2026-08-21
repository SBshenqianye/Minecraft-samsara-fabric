package com.shenqianye.chunkmanager.pojo;


import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.NotNull;

@NoArgsConstructor
@Data
public class RawBannerNodeData {
    private ChunkCoordinate location;
    private BlockFace direction;
    private Extension extension;

    public RawBannerNodeData(ChunkCoordinate location, @NotNull BlockFace direction, Extension extension){
        this.location = location;
        this.extension = extension;
        switch (direction) {
            case NORTH_NORTH_EAST, NORTH_NORTH_WEST -> this.direction = BlockFace.NORTH;
            case SOUTH_SOUTH_EAST, SOUTH_SOUTH_WEST -> this.direction = BlockFace.SOUTH;
            case EAST_NORTH_EAST, EAST_SOUTH_EAST -> this.direction = BlockFace.EAST;
            case WEST_NORTH_WEST, WEST_SOUTH_WEST -> this.direction = BlockFace.WEST;
            default -> this.direction = direction;
        }
    }

    public String toString(){
        return location.toString() + "    " + direction + "\n" + extension;
    }
}
