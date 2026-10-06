package com.yuyuframe.launcheragent.celeritas.v1_8_9.mixin;

//? if >=1.8 {
/*
import net.minecraft.client.world.chunk.ClientChunkCache;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccessor {
    @Accessor("chunks")
    List<WorldChunk> getAllChunks();
}
*///?}