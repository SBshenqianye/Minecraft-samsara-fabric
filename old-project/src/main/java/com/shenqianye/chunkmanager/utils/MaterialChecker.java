package com.shenqianye.chunkmanager.utils;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;

public class MaterialChecker {

    private static final Set<Material> BANNER_MATERIALS = new HashSet<>();

    static {
        BANNER_MATERIALS.add(Material.WHITE_BANNER);
        BANNER_MATERIALS.add(Material.ORANGE_BANNER);
        BANNER_MATERIALS.add(Material.MAGENTA_BANNER);
        BANNER_MATERIALS.add(Material.LIGHT_BLUE_BANNER);
        BANNER_MATERIALS.add(Material.YELLOW_BANNER);
        BANNER_MATERIALS.add(Material.LIME_BANNER);
        BANNER_MATERIALS.add(Material.PINK_BANNER);
        BANNER_MATERIALS.add(Material.GRAY_BANNER);
        BANNER_MATERIALS.add(Material.LIGHT_GRAY_BANNER);
        BANNER_MATERIALS.add(Material.CYAN_BANNER);
        BANNER_MATERIALS.add(Material.PURPLE_BANNER);
        BANNER_MATERIALS.add(Material.BLUE_BANNER);
        BANNER_MATERIALS.add(Material.BROWN_BANNER);
        BANNER_MATERIALS.add(Material.GREEN_BANNER);
        BANNER_MATERIALS.add(Material.RED_BANNER);
        BANNER_MATERIALS.add(Material.BLACK_BANNER);
    }

    public static boolean isBanner(Material material) {
        return BANNER_MATERIALS.contains(material);
    }
}