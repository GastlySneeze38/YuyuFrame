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

/**
 * Migration de l'ancien {@code PowderedSnowFogEnvironmentMixin261} — voir
 * {@link FogSetupWaterMixin261}, même principe. Le givre à l'écran (overlay
 * séparé, PAS du brouillard) reste traité par
 * {@code HudExtractTextureOverlayMixin261} (voir {@code HookPoint#HUD_EXTRACT_TEXTURE_OVERLAY}).
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment")
public abstract class FogSetupPowderedSnowMixin261 {

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (fogData != null && VanillaHookRegistry.dispatch(HookPoint.FOG_SETUP_POWDERED_SNOW, null)) {
            FogPush261.pushFar(fogData);
        }
    }
}
