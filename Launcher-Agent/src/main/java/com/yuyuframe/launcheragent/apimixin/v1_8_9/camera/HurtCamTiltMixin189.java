package com.yuyuframe.launcheragent.apimixin.v1_8_9.camera;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#CAMERA_HURT_TILT} sur 1.8.9 — {@code GameRenderer.bobViewWhenHurt(F)V}
 * ({@code bfk.d(F)V}, privée). Même point que l'ancien {@code MixinGameRenderer189}.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class HurtCamTiltMixin189 {

    @Inject(method = "bobViewWhenHurt(F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$dispatchHurtTilt(float tickDelta, CallbackInfo ci) {
        if (VanillaHookRegistry.dispatch(HookPoint.CAMERA_HURT_TILT, null)) ci.cancel();
    }
}
