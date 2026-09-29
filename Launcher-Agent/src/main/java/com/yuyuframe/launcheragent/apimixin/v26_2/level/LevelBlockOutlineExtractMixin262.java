package com.yuyuframe.launcheragent.apimixin.v26_2.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Porte {@code LevelRendererMixin#afterBlockOutlineExtraction} (fabric-rendering-v1,
 * voir mixinapi/26.1.2) vers {@link HookPoint#LEVEL_BLOCK_OUTLINE_EXTRACT} —
 * recoupe le hook déjà utilisé côté modules existants
 * (beforeRenderBlockOutline sur les brackets antérieurs).
 *
 * <p>26.2 : {@code extractBlockOutline(Camera, LevelRenderState)} a suivi
 * l'extraction du monde dans {@code renderer.extract.LevelExtractor}, nom et
 * descripteur inchangés (vérifié par javap).
 */
@Mixin(targets = "net.minecraft.client.renderer.extract.LevelExtractor")
abstract class LevelBlockOutlineExtractMixin262 {

    @Inject(method = "extractBlockOutline", at = @At("RETURN"))
    private void la$dispatchBlockOutlineExtract(Camera camera, LevelRenderState renderStates, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT, camera);
    }
}
