package com.shenqianye.samsara.season;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.UUID;

/** 一个栈层的元数据（{@code world} 或 {@code world/samsara/stack/stack_<n>}）。 */
public final class StackLayer {
    public int schemaVersion = 2;
    /** 0 = 栈底/最旧；越大越新；栈顶 = layers.size()-1。 */
    public int index;
    /** 栈顶固定为 world；旧层为 stack_<n>。 */
    public String id = StackPaths.TOP_ID;
    /** 创建它的下层世界 id；栈底为空。 */
    public String parent = "";
    public String worldId = "";
    /** 该层的世界种子（独立）。 */
    public long seed;
    /** 创建时的 level-type（normal/flat/amplified/...）。 */
    public String levelType = "normal";
    /** 创建时的 generator-settings（可为空）。 */
    public String generatorSettings = "";
    public long createdAt;
    public int spawnX;
    public int spawnY = 64;
    public int spawnZ;
    public boolean top;

    public StackLayer copy() {
        StackLayer l = new StackLayer();
        l.schemaVersion = schemaVersion;
        l.index = index;
        l.id = id;
        l.parent = parent;
        l.worldId = worldId;
        l.seed = seed;
        l.levelType = levelType;
        l.generatorSettings = generatorSettings;
        l.createdAt = createdAt;
        l.spawnX = spawnX;
        l.spawnY = spawnY;
        l.spawnZ = spawnZ;
        l.top = top;
        return l;
    }

    public static StackLayer top(MinecraftServer server, long seed) {
        StackLayer l = new StackLayer();
        l.id = StackPaths.TOP_ID;
        l.top = true;
        l.seed = seed;
        l.worldId = UUID.randomUUID().toString();
        l.createdAt = System.currentTimeMillis();
        if (server != null) {
            var overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                var pos = overworld.getRespawnData().pos();
                l.spawnX = pos.getX();
                l.spawnY = pos.getY();
                l.spawnZ = pos.getZ();
            }
            l.seed = overworld == null ? seed : overworld.getSeed();
        }
        return l;
    }

    public Path metaDir(Path worldDir) {
        return StackPaths.layerMeta(worldDir, this);
    }

    public Path dimensionDir(Path worldDir, String dim) {
        return StackPaths.layerDimension(worldDir, this, dim);
    }

    public ResourceKey<Level> key(String dim) {
        return StackPaths.key(this, dim);
    }

    public String label() {
        return top ? "world（栈顶）" : id;
    }

    public void normalize() {
        if (id == null || id.isBlank()) {
            id = top ? StackPaths.TOP_ID : StackPaths.STACK_PREFIX + Math.max(0, index);
        }
        if (parent == null) {
            parent = "";
        }
        if (levelType == null || levelType.isBlank()) {
            levelType = "normal";
        }
        if (generatorSettings == null) {
            generatorSettings = "";
        }
        if (worldId == null || worldId.isBlank()) {
            worldId = UUID.nameUUIDFromBytes(("samsara:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
    }
}
