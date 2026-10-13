package com.yuyuframe.launcheragent.apimixin.v26_3.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_ATTACK} sur 26.3 — pendant de
 * {@code EntityAttackMixin261} : {@code MultiPlayerGameMode.attack(Player,
 * Entity)}, à l'entrée. Signature relevée par {@code javap} sur le jar 26.3
 * (2026-10-13), inchangée depuis 26.1.2.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class EntityAttackMixin263 {

    @Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), require = 0)
    private void la$dispatchEntityAttack(Player player, Entity target, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_ATTACK, target);
    }
}
