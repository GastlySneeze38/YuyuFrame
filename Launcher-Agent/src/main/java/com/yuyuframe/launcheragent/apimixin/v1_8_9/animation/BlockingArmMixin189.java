package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * {@link HookPoint#BLOCKING_ARM_YAW} sur 1.8.9 —
 * {@code BiPedModel.setAngles(FFFFFFEntity)V} ({@code bbj.a}).
 *
 * <p>Quand l'entité bloque, vanilla 1.8 tourne le bras droit vers l'intérieur
 * ({@code rightArm.yaw = -0.5235988F}, offset 329 au javap — seule occurrence
 * de la constante dans la méthode). La 1.7 laissait le bras droit : même
 * correction qu'Animatium-Legacy (« 1.7 Third-Person Arm Block Position »),
 * par la constante plutôt que par l'écriture du champ.
 */
@Mixin(targets = "net.minecraft.client.render.entity.model.BiPedModel")
public abstract class BlockingArmMixin189 {

    @ModifyConstant(method = "setAngles(FFFFFFLnet/minecraft/entity/Entity;)V",
        constant = @Constant(floatValue = -0.5235988F), require = 0)
    private float la$blockingArmYaw(float original) {
        Object value = VanillaHookRegistry.dispatchValue(HookPoint.BLOCKING_ARM_YAW, Float.valueOf(original));
        return value instanceof Float ? (Float) value : original;
    }
}
