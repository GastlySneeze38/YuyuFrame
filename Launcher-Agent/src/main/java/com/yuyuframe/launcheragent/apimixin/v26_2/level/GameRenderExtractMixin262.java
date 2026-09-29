package com.yuyuframe.launcheragent.apimixin.v26_2.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code GameRendererMixin#beforeExtract} (fabric-rendering-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#GAME_RENDER_EXTRACT} — tout début de
 * la passe d'extraction générale par frame.
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
abstract class GameRenderExtractMixin262 {

    @Inject(method = "extract", at = @At(value = "HEAD"))
    private void la$dispatchGameRenderExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.GAME_RENDER_EXTRACT, deltaTracker);
    }
}
