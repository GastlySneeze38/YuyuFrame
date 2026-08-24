package com.yuyuframe.launcheragent.apimixin.v26_1.screen;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte (simplifié) {@code ScreenMixin#afterInitScreen} (fabric-screen-api-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#SCREEN_INIT} — l'original Fabric
 * construit ici tout un bus d'events par écran (before/after tick, allow/
 * before/after clic/drag/scroll/touche...) ; on ne porte que le signal
 * "écran initialisé" (TAIL de {@code init(II)V}, éléments déjà peuplés) —
 * les autres granularités (scroll, clavier) ont leur propre HookPoint dédié
 * ailleurs.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
abstract class ScreenInitMixin261 {

    @Inject(method = "init(II)V", at = @At("TAIL"))
    private void la$dispatchScreenInit(int width, int height, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.SCREEN_INIT, this);
    }
}
