package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.ItemCooldown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code HookPoint.ITEM_COOLDOWN} : le serveur met un groupe d'objets de notre
 * joueur en recharge — un bouclier désactivé par une hache passe par là. Voir
 * {@code ItemCooldownMixin263}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class ItemCooldownMixin261 {

    @Inject(method = "handleItemCooldown(Lnet/minecraft/network/protocol/game/ClientboundCooldownPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchItemCooldown(@Coerce Object packet, CallbackInfo ci) {
        ItemCooldown cooldown = CombatEvents261.cooldown(packet);
        if (cooldown != null) {
            VanillaHookRegistry.dispatch(HookPoint.ITEM_COOLDOWN, cooldown);
        }
    }
}
