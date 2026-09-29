package com.yuyuframe.launcheragent.apimixin.v26_2.fog;

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

/** Migration de l'ancien {@code LavaFogEnvironmentMixin261} — voir {@link FogSetupWaterMixin262}, même principe. */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.LavaFogEnvironment")
public abstract class FogSetupLavaMixin262 {

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (fogData != null && VanillaHookRegistry.dispatch(HookPoint.FOG_SETUP_LAVA, null)) {
            FogPush262.pushFar(fogData);
        }
    }
}
