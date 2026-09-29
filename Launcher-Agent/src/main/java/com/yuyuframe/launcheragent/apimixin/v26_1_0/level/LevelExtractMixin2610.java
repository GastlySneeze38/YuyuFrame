package com.yuyuframe.launcheragent.apimixin.v26_1_0.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code LevelRendererMixin#afterExtractLevel} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#LEVEL_EXTRACT} — fin de la
 * passe d'extraction du monde pour cette frame.
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
abstract class LevelExtractMixin2610 {

    @Inject(method = "extractLevel", at = @At("RETURN"))
    private void la$dispatchLevelExtract(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_EXTRACT, camera);
    }
}
