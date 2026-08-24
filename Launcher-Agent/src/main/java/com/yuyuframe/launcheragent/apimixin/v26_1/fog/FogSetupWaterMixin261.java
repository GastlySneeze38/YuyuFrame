package com.yuyuframe.launcheragent.apimixin.v26_1.fog;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code WaterFogEnvironmentMixin261} — voir sa
 * javadoc (historique {@code mixin/client/v26_1/}) pour le crash évité
 * ("No color source environment found", jamais toucher {@code isApplicable})
 * et le crash "non-private static method" qui obligeait chaque ancien Mixin
 * à dupliquer son propre {@code shouldClear()}. Les DEUX raisons disparaissent
 * ici : on ne touche toujours pas {@code isApplicable}, et la logique
 * "no-fog OU clear-vision.clearWater" vit maintenant dans NoFogModule/
 * ClearVisionModule (classes Java normales, pas de restriction Mixin
 * cross-classe) au lieu d'être copiée dans chaque fichier Mixin.
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.WaterFogEnvironment")
public abstract class FogSetupWaterMixin261 {

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (fogData != null) {
            VanillaHookRegistry.dispatch(HookPoint.FOG_SETUP_WATER, fogData);
        }
    }
}
