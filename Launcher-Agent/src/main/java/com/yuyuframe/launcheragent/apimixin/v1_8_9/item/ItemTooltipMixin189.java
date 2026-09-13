package com.yuyuframe.launcheragent.apimixin.v1_8_9.item;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * {@link HookPoint#ITEM_TOOLTIP} sur 1.8.9 — {@code ItemStack.getTooltip(PlayerEntity, boolean)}.
 *
 * <p>Un seul {@code areturn} sur cette version (javap {@code zx.a(wn,Z)}), donc
 * {@code RETURN} sans ordinal. {@code ctx} = la liste renvoyée, dont les
 * éléments sont des {@code String} en 1.8.9 (des {@code Text} en 1.21.11).
 */
@Mixin(targets = "net.minecraft.item.ItemStack")
public abstract class ItemTooltipMixin189 {

    @Inject(method = "getTooltip(Lnet/minecraft/entity/player/PlayerEntity;Z)Ljava/util/List;",
            at = @At("RETURN"), require = 0)
    private void la$dispatchItemTooltip(@Coerce Object player, boolean advanced,
                                        CallbackInfoReturnable<List<String>> info) {
        VanillaHookRegistry.dispatch(HookPoint.ITEM_TOOLTIP, info.getReturnValue());
    }
}
