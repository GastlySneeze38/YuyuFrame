package com.yuyuframe.launcheragent.apimixin.v1_8_9.render;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#HELD_ITEM_TRANSFORM} sur 1.8.9 — {@code HeldItemRenderer}.
 *
 * <p>Remplace trois mixins historiques de {@code mixin/client/v1_8}
 * ({@code MixinDiagonalSword189}, {@code MixinOldBow189},
 * {@code MixinOldItemRotations189}) et la pose de {@code MixinOldConsume189}.
 * Leur logique vit maintenant dans les modules ; il reste ici les QUATRE
 * endroits où l'appliquer (vérifiés javap sur {@code bfn}) :
 * <ul>
 *   <li>{@code "item"} — juste avant {@code renderItem}, seul appel de
 *       {@code renderArmHoldingItem} ;</li>
 *   <li>{@code "bow"} — fin de {@code applyBowTransformation} ;</li>
 *   <li>{@code "rotation"} — début de {@code applyPlayerRotation}, annulable ;</li>
 *   <li>{@code "consume"} — début de {@code applyEatOrDrinkTransformation},
 *       annulable.</li>
 * </ul>
 *
 * <p>Plus de réflexion : les anciens mixins cherchaient {@code mainHand},
 * {@code SwordItem} et {@code GlStateManager} par {@code McReflect} à chaque
 * image. Le genre d'objet et la durée d'utilisation passent par les points
 * d'accès ; la matrice, par {@link HeldItemGl189}.
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class HeldItemTransformMixin189 {

    /** Avancement dans le tick de l'image en cours — l'étape « item » n'a pas de paramètre qui le porte. */
    private static float la$tickDelta;

    @Inject(method = "renderArmHoldingItem(F)V", at = @At("HEAD"), require = 0)
    private void la$captureTickDelta(float tickDelta, CallbackInfo ci) {
        la$tickDelta = tickDelta;
    }

    @Inject(method = "renderArmHoldingItem(F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem("
            + "Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/render/model/json/ModelTransformation$Mode;)V"),
        require = 0)
    private void la$beforeItem(float tickDelta, CallbackInfo ci) {
        la$apply("item", tickDelta);
    }

    /**
     * Étape {@code "bow_pre"} : juste avant la mise à l'échelle qui termine la
     * pose de l'arc ({@code bfl.a(FFF)V}, seul appel de {@code scale} de
     * {@code bfn.a(FLbet;)V}, offset 165 au javap).
     */
    @Inject(method = "applyBowTransformation(FLnet/minecraft/client/network/AbstractClientPlayerEntity;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;scale(FFF)V"),
        require = 0)
    private void la$beforeBowScale(float tickDelta, @Coerce Object player, CallbackInfo ci) {
        la$apply("bow_pre", tickDelta);
    }

    @Inject(method = "applyBowTransformation(FLnet/minecraft/client/network/AbstractClientPlayerEntity;)V",
        at = @At("TAIL"), require = 0)
    private void la$afterBow(float tickDelta, @Coerce Object player, CallbackInfo ci) {
        la$apply("bow", tickDelta);
    }

    @Inject(method = "applyPlayerRotation(Lnet/minecraft/entity/player/ClientPlayerEntity;F)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void la$rotation(CallbackInfo ci) {
        if (la$apply("rotation", la$tickDelta)) ci.cancel();
    }

    @Inject(method = "applyEatOrDrinkTransformation(Lnet/minecraft/client/network/AbstractClientPlayerEntity;F)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void la$consume(CallbackInfo ci) {
        if (la$apply("consume", la$tickDelta)) ci.cancel();
    }

    /** @return {@code true} si un module a fourni une transformation (appliquée). */
    private static boolean la$apply(String stage, float tickDelta) {
        try {
            Object ops = VanillaHookRegistry.dispatchValue(HookPoint.HELD_ITEM_TRANSFORM,
                new Object[]{ stage, Float.valueOf(tickDelta) });
            if (!(ops instanceof MatrixOps)) return false;
            HeldItemGl189.apply((MatrixOps) ops);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[HeldItemTransformMixin189] " + stage + " : " + t);
            return false;
        }
    }
}
