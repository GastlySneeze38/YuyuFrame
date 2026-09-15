package com.yuyuframe.launcheragent.apimixin.v1_8_9.render;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HELD_ITEM_SWING_PROGRESS} sur 1.8.9 —
 * {@code HeldItemRenderer.renderArmHoldingItem(F)V}.
 *
 * <p>Pendant une utilisation, le {@code switch} sur l'action d'usage appelle
 * {@code applyEquipAndSwingOffset(équipement, 0.0F)} : l'avancement du swing est
 * FORCÉ à zéro. Ordre des appels dans la méthode (javap {@code bfn.a(F)V}) :
 * <ol start="0">
 *   <li>action {@code NONE} ;</li>
 *   <li>{@code EAT}/{@code DRINK} → {@code "consume"} ;</li>
 *   <li>{@code BLOCK} → {@code "block"} ;</li>
 *   <li>{@code BOW} → {@code "bow"} ;</li>
 *   <li>hors utilisation (vrai avancement, non touché).</li>
 * </ol>
 * Mêmes ordinaux que les anciens {@code MixinOldConsume189},
 * {@code MixinSwingWhileBlocking189} et {@code MixinOldBow189}, qui
 * capturaient chacun l'avancement par réflexion.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class HeldItemSwingProgressMixin189 {

    private static float la$tickDelta;

    private static final String OFFSET =
        "Lnet/minecraft/client/render/item/HeldItemRenderer;applyEquipAndSwingOffset(FF)V";

    @Inject(method = "renderArmHoldingItem(F)V", at = @At("HEAD"), require = 0)
    private void la$captureTickDelta(float tickDelta, CallbackInfo ci) {
        la$tickDelta = tickDelta;
    }

    @ModifyArg(method = "renderArmHoldingItem(F)V", at = @At(value = "INVOKE", target = OFFSET, ordinal = 1),
        index = 1, require = 0)
    private float la$consume(float original) {
        return la$swingProgress("consume", original);
    }

    @ModifyArg(method = "renderArmHoldingItem(F)V", at = @At(value = "INVOKE", target = OFFSET, ordinal = 2),
        index = 1, require = 0)
    private float la$block(float original) {
        return la$swingProgress("block", original);
    }

    @ModifyArg(method = "renderArmHoldingItem(F)V", at = @At(value = "INVOKE", target = OFFSET, ordinal = 3),
        index = 1, require = 0)
    private float la$bow(float original) {
        return la$swingProgress("bow", original);
    }

    private static float la$swingProgress(String action, float original) {
        try {
            if (!Boolean.TRUE.equals(VanillaHookRegistry.dispatchValue(HookPoint.HELD_ITEM_SWING_PROGRESS, action))) {
                return original;
            }
            Object real = AccessorRegistry.invoke(AccessPoint.PLAYER_SWING_PROGRESS, null, Float.valueOf(la$tickDelta));
            return real instanceof Number ? ((Number) real).floatValue() : original;
        } catch (Throwable t) {
            LauncherLog.err("[HeldItemSwingProgressMixin189] " + action + " : " + t);
            return original;
        }
    }
}
