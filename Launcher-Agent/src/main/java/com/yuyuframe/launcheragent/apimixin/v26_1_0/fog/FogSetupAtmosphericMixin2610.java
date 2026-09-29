package com.yuyuframe.launcheragent.apimixin.v26_1_0.fog;

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
 * Migration de l'ancien {@code AtmosphericFogEnvironmentMixin261} (voir
 * historique {@code mixin/client/v26_1/}) — plus aucune logique ici, la
 * décision "faut-il repousser le brouillard" appartient désormais à
 * NoFogModule (seul module enregistré sur {@link HookPoint#FOG_SETUP_ATMOSPHERIC}).
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment")
public abstract class FogSetupAtmosphericMixin2610 {

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (fogData != null && VanillaHookRegistry.dispatch(HookPoint.FOG_SETUP_ATMOSPHERIC, null)) {
            FogPush2610.pushFar(fogData);
        }
    }
}
