package com.shenqianye.chunkmanager.listener;

import com.shenqianye.chunkmanager.pojo.*;
import com.shenqianye.chunkmanager.utils.MaterialChecker;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.List;

public class BannerPlacementListener implements Listener {
    @EventHandler
    public void onPlayerPlaceBanner(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        // 确保放置方块时手上拿着的物品是旗帜而非其他东西
        if (!MaterialChecker.isBanner(itemInHand.getType())){
            return;
        }

        // 如果放置的旗帜没有任何花色，则不把旗帜作为圈地节点
        BannerMeta bannerMeta = (BannerMeta) itemInHand.getItemMeta();
        if (bannerMeta == null) {
            return;
        }

        // 构建旗帜节点
        Node node = buildNode(event);
        Player player = event.getPlayer();
        player.sendMessage(node.toString());

        // 处理节点
        Area area = new Area();
        switch(area.detectPositionOfNode(node)){
            case INNER -> {
                return;
            }
            case INFLUENCE -> {
                area.extendEndOfAreaTo(node);
            }
            case OUTER -> {}
            default -> throw new IllegalStateException("Unexpected value: " + area.detectPositionOfNode(node));
        }
        switch (area.detectInfluencedNodesAround(node)){
            case ABSENT->{
                area.createNewArea(node);
            }
            case PRESENT -> {
                area.extendStartOfAreaAt(node);
            }
        }
        // 合并区域
        area.mergeSegmentGroups();
        // 更新白名单区域坐标集、受影响区域坐标集、节点坐标集
        area.generateWhitelistCoordinates();
    }

    private Node buildNode(BlockPlaceEvent event){
        ItemStack itemInHand = event.getItemInHand();
        BannerMeta bannerMeta = (BannerMeta) itemInHand.getItemMeta();
        assert bannerMeta != null;
        List<Pattern> patterns = bannerMeta.getPatterns();
        // 获取样式
        Extension extension = new Extension();
        for (Pattern pattern : patterns) {
            PatternType patternType = pattern.getPattern();
            DyeColor dyeColor = pattern.getColor();
            extension.addDirection(patternType, dyeColor);
        }
        // 获取区块坐标
        Block banner = event.getBlockPlaced();
        Chunk chunk = banner.getChunk();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        ChunkCoordinate coordinate = new ChunkCoordinate(chunkX, chunkZ);
        // 获取旗帜朝向
        BlockData blockData = banner.getBlockData();
        BlockFace rotation = ((Rotatable) blockData).getRotation();
        RawBannerNodeData rawBannerNodeData = new RawBannerNodeData(coordinate, rotation, extension);
        return new Node(rawBannerNodeData);
    }

    @Deprecated
    boolean isBanner(Material material){
        return switch (material) {
            case WHITE_BANNER, ORANGE_BANNER, MAGENTA_BANNER, LIGHT_BLUE_BANNER, YELLOW_BANNER, LIME_BANNER,
                    PINK_BANNER, GRAY_BANNER, LIGHT_GRAY_BANNER, CYAN_BANNER, PURPLE_BANNER, BLUE_BANNER,
                    BROWN_BANNER, GREEN_BANNER, RED_BANNER, BLACK_BANNER ->
                    true;
            default -> false;
        };
    }
}
