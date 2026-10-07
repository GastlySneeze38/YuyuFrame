package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Encadre le dessin de la poupée du joueur dans les écrans
 * ({@code SurvivalInventoryScreen.renderEntity}, statique, {@code azc.a(IIIFFLpr;)V},
 * appelée aussi par l'inventaire créatif) : pendant ce dessin, « Mon pseudo »
 * ne s'applique pas. Voir {@link OwnLabel189}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen")
public abstract class InventoryDollMixin189 {

    @Inject(method = "renderEntity(IIIFFLnet/minecraft/entity/LivingEntity;)V", at = @At("HEAD"), require = 0)
    private static void la$dollBegins(int x, int y, int size, float mouseX, float mouseY, @Coerce Object entity,
                                      CallbackInfo ci) {
        OwnLabel189.dollBegins();
    }

    @Inject(method = "renderEntity(IIIFFLnet/minecraft/entity/LivingEntity;)V", at = @At("RETURN"), require = 0)
    private static void la$dollEnds(int x, int y, int size, float mouseX, float mouseY, @Coerce Object entity,
                                    CallbackInfo ci) {
        OwnLabel189.dollEnds();
    }
}
