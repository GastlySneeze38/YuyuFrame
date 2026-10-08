package com.yuyuframe.launcheragent.apimixin.v1_21_11.player;

import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Encadre la copie de l'état de la poupée du joueur dans l'inventaire
 * ({@code InventoryScreen.drawEntity(LivingEntity)}, statique et privée,
 * {@code gul.a(Lchl;)Lidf;}) : c'est pendant cette copie que le jeu décide si
 * l'étiquette s'affiche, et « Nametag » ne doit pas s'appliquer à la poupée.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.InventoryScreen")
public abstract class InventoryDollMixin1211 {

    @Inject(method = "drawEntity(Lnet/minecraft/entity/LivingEntity;)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
            at = @At("HEAD"), require = 0)
    private static void la$dollBegins(@Coerce Object entity, CallbackInfoReturnable<Object> cir) {
        PlayerBadge.dollBegins();
    }

    @Inject(method = "drawEntity(Lnet/minecraft/entity/LivingEntity;)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
            at = @At("RETURN"), require = 0)
    private static void la$dollEnds(@Coerce Object entity, CallbackInfoReturnable<Object> cir) {
        PlayerBadge.dollEnds();
    }
}
