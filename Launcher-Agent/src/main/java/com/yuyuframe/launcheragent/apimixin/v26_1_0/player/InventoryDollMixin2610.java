package com.yuyuframe.launcheragent.apimixin.v26_1_0.player;

import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Encadre la copie de l'état de la poupée du joueur dans l'inventaire
 * ({@code InventoryScreen.extractRenderState(LivingEntity)}, statique et
 * privée) : c'est pendant cette copie que le jeu décide si l'étiquette
 * s'affiche, et « Nametag » ne doit pas s'appliquer à la poupée.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.InventoryScreen")
abstract class InventoryDollMixin2610 {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("HEAD"), require = 0)
    private static void la$dollBegins(@Coerce Object entity, CallbackInfoReturnable<Object> cir) {
        PlayerBadge.dollBegins();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"), require = 0)
    private static void la$dollEnds(@Coerce Object entity, CallbackInfoReturnable<Object> cir) {
        PlayerBadge.dollEnds();
    }
}
