package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#ENTITY_ATTACK} — {@code MultiPlayerGameMode.attack(Player,
 * Entity)}, à l'entrée : le moment où le client décide d'attaquer, avant
 * l'envoi du paquet. Signature relevée par {@code javap} sur le jar 26.1.2
 * (2026-10-13).
 *
 * <p>{@code Player} et {@code Entity} sont déjà stubés pour cette tranche
 * (voir {@code EntityLoadMixin261}, qui capture {@code Entity} de la même
 * façon) : aucun stub ajouté.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class EntityAttackMixin261 {

    @Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), require = 0)
    private void la$dispatchEntityAttack(Player player, Entity target, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.ENTITY_ATTACK, target);
    }
}
