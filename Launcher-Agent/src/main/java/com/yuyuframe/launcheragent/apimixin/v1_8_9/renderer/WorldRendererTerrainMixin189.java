package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.RendererHooks189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Branche le renderer de terrain sur le {@code WorldRenderer} de la 1.8.9
 * ({@code bfr}) — portage de {@code WorldRendererMixin} de l'adaptateur
 * {@code ornithe} de Celeritas.
 *
 * <p><b>Écart voulu avec l'amont</b> : là-bas, cinq {@code @Overwrite}
 * remplacent le rendu vanilla sans retour possible. Ici, chaque méthode est
 * interceptée en tête et n'est annulée que si {@link RendererSwitch#active()}
 * est vrai : éteint, le rendu vanilla tourne tel quel, et le module peut
 * basculer en cours de partie. Toute la logique est dans
 * {@link RendererHooks189} — ce fichier ne nomme aucun type du jeu.
 *
 * <p>Quand le renderer est actif, vanilla ne remplit plus sa liste de chunks
 * visibles ({@code setupTerrain} annulée). Conséquence : ses boucles d'entités
 * et de block entities, qui parcourent cette liste, ne dessinent plus rien —
 * d'où les deux injections dans {@code renderEntities}.
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class WorldRendererTerrainMixin189 {

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    /** Fin du rechargement des chunks : le seul moment où l'on change de renderer. */
    @Inject(method = "reload()V", at = @At("RETURN"), require = 0)
    private void la$afterReload(CallbackInfo ci) {
        RendererHooks189.onReload(this);
    }

    @Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;)V", at = @At("RETURN"), require = 0)
    private void la$afterSetWorld(@Coerce Object world, CallbackInfo ci) {
        RendererHooks189.onWorldChanged(world);
    }

    // ── Terrain ──────────────────────────────────────────────────────────────

    @Inject(method = "setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/render/CameraView;IZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$setupTerrain(@Coerce Object entity, double tickDelta, @Coerce Object cameraView, int frame,
                                 boolean spectator, CallbackInfo ci) {
        if (!RendererSwitch.active()) return;
        RendererHooks189.setupTerrain(this, cameraView, tickDelta);
        ci.cancel();
    }

    @Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void la$renderLayer(@Coerce Object layer, double tickDelta, int anaglyphPass, @Coerce Object entity,
                                CallbackInfoReturnable<Integer> cir) {
        if (!RendererSwitch.active()) return;
        RendererHooks189.renderLayer(layer, tickDelta, entity);
        cir.setReturnValue(1);
    }

    /** Vanilla n'a plus de chunks à construire : c'est le moteur qui planifie. */
    @Inject(method = "updateChunks(J)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$updateChunks(long limitTime, CallbackInfo ci) {
        if (RendererSwitch.active()) ci.cancel();
    }

    /** Des blocs ont changé : à reconstruire chez nous. */
    @Inject(method = "updateBlock(IIIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$updateBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RendererSwitch.active()) return;
        RendererHooks189.scheduleRebuild(minX, minY, minZ, maxX, maxY, maxZ);
        ci.cancel();
    }

    @Inject(method = "getChunksDebugString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$chunksDebugString(CallbackInfoReturnable<String> cir) {
        if (RendererSwitch.active()) cir.setReturnValue(RendererHooks189.chunksDebugString());
    }

    // ── Entités et block entities ────────────────────────────────────────────

    /** Au passage du profileur sur « entities » : là où vanilla commence à dessiner les siennes. */
    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=entities"),
            require = 0)
    private void la$renderEntities(@Coerce Object camera, @Coerce Object cameraView, float tickDelta, CallbackInfo ci) {
        if (RendererSwitch.active()) RendererHooks189.renderEntities(this, camera, cameraView, tickDelta);
    }

    /** Au passage du profileur sur « blockentities » : là où vanilla commence à dessiner les siens. */
    @Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
            at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=blockentities"),
            require = 0)
    private void la$renderBlockEntities(@Coerce Object camera, @Coerce Object cameraView, float tickDelta, CallbackInfo ci) {
        if (RendererSwitch.active()) RendererHooks189.renderBlockEntities(tickDelta);
    }
}
