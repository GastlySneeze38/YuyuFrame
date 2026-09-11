package com.yuyuframe.launcheragent.apimixin.v1_21_11.item;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * {@link HookPoint#ITEM_TOOLTIP} sur 1.21.11 — pendant de {@code ItemTooltipMixin261}.
 *
 * <pre>
 *   26.1.2 : ItemStack.getTooltipLines(Item$TooltipContext, Player, TooltipFlag)
 *   1.21.11 : ItemStack.getTooltip(Item$TooltipContext, PlayerEntity, TooltipType)
 * </pre>
 *
 * {@code RETURN ordinal = 1} comme en 26.1.2 : vérifié javap, la méthode a
 * DEUX {@code areturn} — le premier est la sortie anticipée (infobulle
 * masquée), le second la liste complète. ctx = la {@code List} renvoyée
 * (ses éléments sont des {@code Text} de cette version).
 */
@Mixin(targets = "net.minecraft.item.ItemStack")
public abstract class ItemTooltipMixin1211 {

    @Inject(method = "getTooltip(Lnet/minecraft/item/Item$TooltipContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;",
            at = @At(value = "RETURN", ordinal = 1), require = 0)
    private void la$dispatchItemTooltip(@Coerce Object tooltipContext, @Coerce Object player, @Coerce Object tooltipType,
                                        CallbackInfoReturnable<List<Object>> info) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_TOOLTIP, info.getReturnValue());
    }
}
