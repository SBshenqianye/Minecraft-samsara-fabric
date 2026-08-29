package com.shenqianye.samsara.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor：暴露 {@code ChunkMap.toDrop}（已标记卸载的区块集合）。
 * <p>
 * 用于 SafeDeletion.execute() 在运行时强制卸载非白名单区块，
 * 使 MC 在下次加载时从种子重新生成自然地形。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("toDrop")
    LongSet samsara$getToDrop();
}
