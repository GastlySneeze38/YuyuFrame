package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@link HookPoint#CAMERA_EYE_HEIGHT} sur 1.8.9 —
 * {@code GameRenderer.transformCamera(F)V} ({@code bfk.f}).
 *
 * <p>Premier stockage de flottant de la méthode : {@code f = entity.getEyeHeight()}
 * (javap : {@code pk.aS()F} puis {@code fstore_3}, offsets 9-12 ; local 3 =
 * après {@code this}, {@code tickDelta} et l'entité). Toute la pose de la
 * caméra part de cette hauteur : la remplacer suffit à adoucir le passage
 * accroupi/debout, que vanilla 1.8 fait d'un coup. Même point qu'Animatium-Legacy
 * ({@code orientCamera}, variable {@code f}).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class SmoothSneakCameraMixin189 {

    @ModifyVariable(method = "transformCamera(F)V", at = @At(value = "STORE", ordinal = 0), index = 3, require = 0)
    private float la$eyeHeight(float eyeHeight) {
        Object value = VanillaHookRegistry.dispatchValue(HookPoint.CAMERA_EYE_HEIGHT, Float.valueOf(eyeHeight));
        return value instanceof Float ? (Float) value : eyeHeight;
    }
}
