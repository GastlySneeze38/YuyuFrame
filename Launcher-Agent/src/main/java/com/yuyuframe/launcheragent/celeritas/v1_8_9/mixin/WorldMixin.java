package com.yuyuframe.launcheragent.celeritas.v1_8_9.mixin;

import net.minecraft.world.World;
import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkTracker;
import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(World.class)
public class WorldMixin implements ChunkTrackerHolder {
    private final ChunkTracker celeritas$tracker = new ChunkTracker();

    @Override
    public ChunkTracker sodium$getTracker() {
        return celeritas$tracker;
    }
}
