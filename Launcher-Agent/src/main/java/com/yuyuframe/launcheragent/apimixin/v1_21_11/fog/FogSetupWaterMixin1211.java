package com.yuyuframe.launcheragent.apimixin.v1_21_11.fog;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#FOG_SETUP_WATER} sur 1.21.11 — pendant de
 * {@code FogSetupWaterMixin261}, même point (TAIL de {@code setupFog}).
 *
 * <pre>
 *   26.1.2 : WaterFogEnvironment.setupFog(FogData, Camera, ClientLevel, float, DeltaTracker)
 *   1.21.11 : WaterFogModifier.applyStartEndModifier(FogData, Camera, ClientWorld, float, RenderTickCounter)
 * </pre>
 *
 * Mojang : même classe, même méthode, même signature qu'en 26.1.2 ; seul Yarn
 * les nomme autrement. La méthode n'est déclarée dans Yarn que sur
 * {@code FogModifier} : son entrée de refmap porte ce parent en repli.
 * On ne touche toujours pas {@code isApplicable} (crash « No color source
 * environment found », voir la javadoc 26.1.2).
 *
 * <p>Même forme pour les six environnements : {@link FogPush1211}.
 */
@Mixin(targets = "net.minecraft.client.render.fog.WaterFogModifier")
public abstract class FogSetupWaterMixin1211 {

    @Inject(method = "applyStartEndModifier(Lnet/minecraft/client/render/fog/FogData;Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(@Coerce Object fogData, @Coerce Object camera, @Coerce Object world,
                                     float partialTick, @Coerce Object tickCounter, CallbackInfo ci) {
        FogPush1211.dispatch(HookPoint.FOG_SETUP_WATER, fogData);
    }
}
