package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.v1_8_9.RendererHooks189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prévient le renderer quand un chunk du client est chargé ou déchargé —
 * portage de {@code MultiplayerChunkCacheMixin} de l'adaptateur {@code ornithe}
 * de Celeritas.
 *
 * <p>Le moteur ne construit une section que si son chunk ET ses voisins sont
 * là : il tient pour ça sa propre carte des chunks chargés
 * ({@code ChunkTracker}, voir {@link WorldChunkTrackerMixin189}).
 *
 * <p>Tourne que le renderer soit actif ou non : la carte doit être juste au
 * moment où il s'allume en cours de partie.
 */
@Mixin(targets = "net.minecraft.world.chunk.ClientChunkProvider")
public abstract class ClientChunkTrackerMixin189 {

    @Inject(method = "getOrGenerateChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"), require = 0)
    private void la$afterChunkLoaded(int x, int z, CallbackInfoReturnable<Object> cir) {
        RendererHooks189.onClientChunk(x, z, true);
    }

    @Inject(method = "unloadChunk(II)V", at = @At("RETURN"), require = 0)
    private void la$afterChunkUnloaded(int x, int z, CallbackInfo ci) {
        RendererHooks189.onClientChunk(x, z, false);
    }
}
