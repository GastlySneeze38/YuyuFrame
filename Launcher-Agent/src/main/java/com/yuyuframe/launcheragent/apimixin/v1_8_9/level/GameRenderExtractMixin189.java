package com.yuyuframe.launcheragent.apimixin.v1_8_9.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#GAME_RENDER_EXTRACT} sur 1.8.9 — début de
 * {@code GameRenderer.render(float, long)}. {@code ctx} = {@code null} (pas de
 * {@code RenderTickCounter} sur cette version, le delta est un {@code float}).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GameRenderExtractMixin189 {

    @Inject(method = "render(FJ)V", at = @At("HEAD"), require = 0)
    private void la$dispatchGameRenderExtract(float tickDelta, long nanoTime, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.GAME_RENDER_EXTRACT, null);
    }
}
