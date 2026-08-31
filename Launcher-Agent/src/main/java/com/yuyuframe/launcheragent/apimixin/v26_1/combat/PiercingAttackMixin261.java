package com.yuyuframe.launcheragent.apimixin.v26_1.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Le coup de lance chargé, côté CLIENT — {@code
 * MultiPlayerGameMode.piercingAttack(PiercingWeapon)}, découvert en cherchant
 * qui, dans le jar 26.1.2, référence « Piercing » hors du paquet
 * enchantement. C'est ce qui rend le lunge observable sans rien installer
 * côté serveur : le client déclenche l'attaque lui-même.
 *
 * <p>Le paramètre {@code PiercingWeapon} n'est PAS capturé : un handler
 * {@code @Inject} peut se contenter du {@code CallbackInfo} (même choix que
 * {@code ChatReceiveMixin261.la$dispatchPlayerChatMessage}), ce qui évite de
 * stuber un type de plus — et surtout d'exposer un Mixin à un stub faux, la
 * panne la plus coûteuse de ce projet (voir
 * docs/LauncherAgent/module-bracket-audit.md).
 *
 * <p>TAIL et non HEAD : on ne veut compter le coût qu'une fois l'attaque
 * réellement partie.
 *
 * <p>Notification pure — le retour de {@code dispatch} est ignoré, rien n'est
 * annulable ici.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
abstract class PiercingAttackMixin261 {

    @Inject(method = "piercingAttack", at = @At("TAIL"), require = 0)
    private void la$dispatchPiercingAttack(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.PIERCING_ATTACK, null);
    }
}
