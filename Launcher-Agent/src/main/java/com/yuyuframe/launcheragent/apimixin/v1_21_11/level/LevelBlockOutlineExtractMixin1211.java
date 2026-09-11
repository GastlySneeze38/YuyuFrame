package com.yuyuframe.launcheragent.apimixin.v1_21_11.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#LEVEL_BLOCK_OUTLINE_EXTRACT} sur 1.21.11 — pendant EXACT de
 * {@code LevelBlockOutlineExtractMixin261} :
 * {@code LevelRenderer.extractBlockOutline(Camera, LevelRenderState)}, RETURN.
 *
 * <p>⚠️ <b>Nom Yarn trompeur.</b> Yarn 1.21.11 appelle cette méthode
 * {@code fillEntityOutlineRenderStates} ; c'est pourtant bien le contour de
 * BLOC : Mojang la nomme {@code extractBlockOutline} (officiel
 * {@code hoh.b(ger,ikq)}), et le bytecode commence par un {@code instanceof}
 * sur {@code BlockHitResult} (javap). On vise l'identité officielle, pas le
 * sens du nom.
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class LevelBlockOutlineExtractMixin1211 {

    @Inject(method = "fillEntityOutlineRenderStates(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/state/WorldRenderState;)V",
            at = @At("RETURN"), require = 0)
    private void la$dispatchBlockOutlineExtract(@Coerce Object camera, @Coerce Object renderState, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT, camera);
    }
}
