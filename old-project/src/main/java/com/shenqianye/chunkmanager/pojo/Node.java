package com.shenqianye.chunkmanager.pojo;

import lombok.Data;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

@Data
public class Node{
    private ChunkCoordinate location;
    private Extension extension;

    private List<ChunkCoordinate> influencedCoordination;

    public Node(RawBannerNodeData rawData) {
        this.location = rawData.getLocation();
        Extension extension = rawData.getExtension();
        BlockFace direction = rawData.getDirection();
        switch (direction) {
            case NORTH -> this.extension = extension;
            case NORTH_EAST -> this.extension = rotateClockwise(extension, 7);
            case EAST -> this.extension = rotateClockwise(extension, 6);
            case SOUTH_EAST -> this.extension = rotateClockwise(extension, 5);
            case SOUTH -> this.extension = rotateClockwise(extension, 4);
            case SOUTH_WEST -> this.extension = rotateClockwise(extension, 3);
            case WEST -> this.extension = rotateClockwise(extension, 2);
            case NORTH_WEST -> this.extension = rotateClockwise(extension, 1);
        }
    }

    private Extension rotate45DegreeClockwise(Extension extension){
        int temp = extension.getUp();
        extension.setUp(extension.getRightUp());
        extension.setRightUp(extension.getRight());
        extension.setRight(extension.getRightDown());
        extension.setRightDown(extension.getDown());
        extension.setDown(extension.getLeftDown());
        extension.setLeftDown(extension.getLeft());
        extension.setLeft(extension.getLeftUp());
        extension.setLeftUp(temp);
        return extension;
    }

    private Extension rotateClockwise(Extension extension, int frequency){
        for (int i = 0; i < frequency; i++){
            extension = rotate45DegreeClockwise(extension);
        }
        return extension;
    }

    public String toString(){
        return location.toString() + "\n" + extension;
    }

    public List<ChunkCoordinate> getInfluencedCoordination(){
        if (influencedCoordination != null){
            return influencedCoordination;
        }
        List<ChunkCoordinate> coordination = new ArrayList<>();
        for (int i = 1; i <= extension.getUp(); i++){
            ChunkCoordinate e = location.getNorth(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getDown(); i++){
            ChunkCoordinate e = location.getSouth(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getLeft(); i++){
            ChunkCoordinate e = location.getWest(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getRight(); i++){
            ChunkCoordinate e = location.getEast(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getLeftUp(); i++){
            ChunkCoordinate e = location.getNorthWest(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getRightUp(); i++){
            ChunkCoordinate e = location.getNorthEast(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getLeftDown(); i++){
            ChunkCoordinate e = location.getSouthWest(i);
            coordination.add(e);
        }
        for (int i = 1; i <= extension.getRightDown(); i++){
            ChunkCoordinate e = location.getSouthEast(i);
            coordination.add(e);
        }
        influencedCoordination = coordination;
        return coordination;
    }
}
