package com.yuyuframe.launcheragent.apimixin.v1_21_11.fog;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link HookPoint#FOG_SETUP_BLINDNESS} sur 1.21.11 — voir {@link FogSetupWaterMixin1211}, même principe. */
@Mixin(targets = "net.minecraft.client.render.fog.BlindnessEffectFogModifier")
public abstract class FogSetupBlindnessMixin1211 {

    @Inject(method = "applyStartEndModifier(Lnet/minecraft/client/render/fog/FogData;Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchFogSetup(@Coerce Object fogData, @Coerce Object camera, @Coerce Object world,
                                     float partialTick, @Coerce Object tickCounter, CallbackInfo ci) {
        FogPush1211.dispatch(HookPoint.FOG_SETUP_BLINDNESS, fogData);
    }
}
