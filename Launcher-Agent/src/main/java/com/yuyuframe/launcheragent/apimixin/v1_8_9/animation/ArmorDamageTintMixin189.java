package com.yuyuframe.launcheragent.apimixin.v1_8_9.animation;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#ARMOR_DAMAGE_TINT} sur 1.8.9 —
 * {@code ArmorFeatureRenderer.combineTextures()Z} ({@code bkn.b()Z}).
 *
 * <p>Le rendu d'une entité touchée n'applique sa teinte rouge qu'aux couches
 * qui répondent {@code true} ici ; l'armure répond {@code false} en 1.8
 * (javap : {@code iconst_0; ireturn}), d'où une armure qui reste de sa couleur
 * sur un joueur frappé. Répondre {@code true} rend l'« armure rouge » de la
 * 1.7 — même approche que Sk1er OldAnimations et Animatium-Legacy.
 */
@Mixin(targets = "net.minecraft.client.render.entity.feature.ArmorFeatureRenderer")
public abstract class ArmorDamageTintMixin189 {

    @Inject(method = "combineTextures()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$tintArmor(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(VanillaHookRegistry.dispatchValue(HookPoint.ARMOR_DAMAGE_TINT, null))) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
