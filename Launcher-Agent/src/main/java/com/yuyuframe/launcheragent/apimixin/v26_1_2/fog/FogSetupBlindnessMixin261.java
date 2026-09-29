package com.yuyuframe.launcheragent.apimixin.v26_1_2.fog;

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

/** Migration de l'ancien {@code BlindnessFogEnvironmentMixin261} — voir {@link FogSetupAtmosphericMixin261}, même principe (no-fog uniquement). */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment")
public abstract class FogSetupBlindnessMixin261 {

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (fogData != null && VanillaHookRegistry.dispatch(HookPoint.FOG_SETUP_BLINDNESS, null)) {
            FogPush261.pushFar(fogData);
        }
    }
}
