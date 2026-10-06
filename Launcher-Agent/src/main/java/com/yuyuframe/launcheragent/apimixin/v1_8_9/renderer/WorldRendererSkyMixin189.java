package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.RendererHooks189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ciel et nuages du {@code WorldRenderer} de la 1.8.9 ({@code bfr}), quand le
 * renderer de terrain est actif. Toute la logique est dans
 * {@link RendererHooks189} — ce fichier ne nomme aucun type du jeu.
 *
 * <ul>
 *   <li><b>Nuages détaillés</b> : remplacés par un maillage gardé sur la carte
 *       graphique ({@code celeritas/v1_8_9/sky/CloudRenderer189}). Les nuages
 *       « rapides » restent ceux de vanilla : une seule dalle plate, déjà en
 *       un dessin.</li>
 *   <li><b>Ciel</b> : pas dessiné quand le brouillard est rapproché (sous
 *       l'eau, dans la lave, cécité). Le renderer n'envoie pas les chunks que
 *       le brouillard cache : sans ça, on verrait le ciel à leur place. C'est
 *       le correctif de Sodium, pas une optimisation.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class WorldRendererSkyMixin189 {

    /** {@code anaglyphPass} vaut 2 hors anaglyphe ; en anaglyphe vanilla teinte les nuages, on le laisse faire. */
    @Inject(method = "renderFancyClouds(FI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$renderFancyClouds(float tickDelta, int anaglyphPass, CallbackInfo ci) {
        if (!RendererSwitch.active() || anaglyphPass != 2) return;
        RendererHooks189.renderFancyClouds(this, tickDelta);
        ci.cancel();
    }

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$renderSky(float tickDelta, int anaglyphPass, CallbackInfo ci) {
        if (RendererSwitch.active() && RendererHooks189.isSkyHidden(tickDelta)) ci.cancel();
    }
}
