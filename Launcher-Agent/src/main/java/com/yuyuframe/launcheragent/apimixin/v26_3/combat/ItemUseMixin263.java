package com.yuyuframe.launcheragent.apimixin.v26_3.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.ItemUse;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link HookPoint#ITEM_USE} sur 26.3 — pendant de {@code ItemUseMixin261} :
 * {@code MultiPlayerGameMode.useItem(Player, InteractionHand)}, à l'ENTRÉE
 * (à la sortie, la pile a pu tomber à zéro). Signature relevée par
 * {@code javap} sur le jar 26.3 (2026-10-13), inchangée depuis 26.1.2.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class ItemUseMixin263 {

    @Inject(method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), require = 0)
    private void la$dispatchItemUse(Player player, InteractionHand hand, CallbackInfoReturnable<?> cir) {
        ItemUse use = CombatEvents263.itemUse(player, hand);
        if (use != null) {
            VanillaHookRegistry.dispatch(HookPoint.ITEM_USE, use);
        }
    }
}
