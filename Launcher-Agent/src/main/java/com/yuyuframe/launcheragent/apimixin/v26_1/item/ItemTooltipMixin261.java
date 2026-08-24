package com.yuyuframe.launcheragent.apimixin.v26_1.item;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Porte {@code ItemStackMixin#getTooltip} (fabric-item-api-v1, voir
 * mixinapi/26.1.2) vers {@link HookPoint#ITEM_TOOLTIP} — ordinal=1 sur
 * {@code getTooltipLines} : le premier RETURN correspond au cas "pas de
 * tooltip", seul le second nous intéresse (commentaire déjà présent côté
 * Fabric d'origine, vérifié).
 */
@Mixin(targets = "net.minecraft.world.item.ItemStack")
abstract class ItemTooltipMixin261 {

    @Inject(method = "getTooltipLines", at = @At(value = "RETURN", ordinal = 1))
    private void la$dispatchItemTooltip(Item.TooltipContext tooltipContext, Player entity, TooltipFlag tooltipFlag, CallbackInfoReturnable<List<Component>> info) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_TOOLTIP, info.getReturnValue());
    }
}
