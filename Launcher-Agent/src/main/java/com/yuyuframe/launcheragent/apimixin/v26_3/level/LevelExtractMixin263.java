package com.yuyuframe.launcheragent.apimixin.v26_3.level;

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
 *
 * <p>26.2 : l'extraction a quitté {@code LevelRenderer} pour sa propre classe,
 * {@code renderer.extract.LevelExtractor} — {@code LevelRenderer.extractLevel}
 * y devient {@code extract}, même descripteur
 * {@code (DeltaTracker, Camera, float)V} (vérifié par javap).
 */
@Mixin(targets = "net.minecraft.client.renderer.extract.LevelExtractor")
abstract class LevelExtractMixin263 {

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", at = @At("RETURN"))
    private void la$dispatchLevelExtract(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_EXTRACT, camera);
    }
}
