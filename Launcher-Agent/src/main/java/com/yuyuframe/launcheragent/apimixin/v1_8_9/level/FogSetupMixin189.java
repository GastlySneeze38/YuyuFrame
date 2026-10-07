package com.yuyuframe.launcheragent.apimixin.v1_8_9.level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Les points d'accroche {@code FOG_SETUP_*} sur 1.8.9 — un seul mixin là où
 * les versions récentes en ont un par source de brouillard : ici tout se
 * règle dans {@code GameRenderer.renderFog(int, float)} ({@code bfk.a(IF)V}).
 * Le travail est dans {@link FogPush189}.
 *
 * <p>À la sortie, pas à l'entrée : vanilla a fini de poser son brouillard, on
 * ne fait que le repousser quand un module le demande.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class FogSetupMixin189 {

    @Inject(method = "renderFog(IF)V", at = @At("RETURN"), require = 0)
    private void la$pushFog(int pass, float tickDelta, CallbackInfo ci) {
        FogPush189.afterVanilla();
    }
}
