package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkTracker;
import com.yuyuframe.launcheragent.celeritas.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Donne à chaque monde sa carte des chunks chargés — portage de
 * {@code WorldMixin} de l'adaptateur {@code ornithe} de Celeritas.
 *
 * <p>Le moteur la demande par {@code ChunkTrackerHolder.get(world)} : le monde
 * doit donc implémenter cette interface. Elle est alimentée par
 * {@link ClientChunkTrackerMixin189}.
 *
 * <p>Créée À LA DEMANDE, et non avec le monde comme en amont :
 * {@code ChunkTracker} repose sur fastutil, absent quand le launcher est plus
 * ancien que l'agent. La créer d'office ferait planter la création de tout
 * monde ; ainsi, seul le renderer (qui vérifie
 * {@code RendererSwitch.available()}) y touche.
 */
@Mixin(targets = "net.minecraft.world.World")
public abstract class WorldChunkTrackerMixin189 implements ChunkTrackerHolder {

    @Unique
    private ChunkTracker la$chunkTracker;

    @Override
    public ChunkTracker sodium$getTracker() {
        if (this.la$chunkTracker == null) {
            this.la$chunkTracker = new ChunkTracker();
        }
        return this.la$chunkTracker;
    }
}
