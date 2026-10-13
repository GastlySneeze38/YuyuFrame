package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

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
 * {@link HookPoint#ITEM_USE} — {@code MultiPlayerGameMode.useItem(Player,
 * InteractionHand)}, à l'ENTRÉE : à la sortie, la pile a pu tomber à zéro et
 * la main ne dit plus ce qui vient d'être utilisé. Signature relevée par
 * {@code javap} sur le jar 26.1.2 (2026-10-13) ; elle rend un
 * {@code InteractionResult}, d'où le {@code CallbackInfoReturnable}.
 *
 * <p>Le type de retour n'est pas nommé ({@code <?>}) : il faudrait le stuber
 * pour rien, on ne le lit pas.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class ItemUseMixin261 {

    @Inject(method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), require = 0)
    private void la$dispatchItemUse(Player player, InteractionHand hand, CallbackInfoReturnable<?> cir) {
        ItemUse use = CombatEvents261.itemUse(player, hand);
        if (use != null) {
            VanillaHookRegistry.dispatch(HookPoint.ITEM_USE, use);
        }
    }
}
