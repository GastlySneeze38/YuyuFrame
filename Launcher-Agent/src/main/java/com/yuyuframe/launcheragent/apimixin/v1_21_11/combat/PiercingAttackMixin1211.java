package com.yuyuframe.launcheragent.apimixin.v1_21_11.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#PIERCING_ATTACK} sur 1.21.11 — pendant de {@code PiercingAttackMixin261}.
 *
 * <pre>
 *   26.1.2 : MultiPlayerGameMode.piercingAttack(PiercingWeapon)
 *   1.21.11 : ClientPlayerInteractionManager.attackWithPiercingWeapon(PiercingWeaponComponent)
 * </pre>
 *
 * La lance et son enchantement « lunge » existent déjà en 1.21.11 (vérifié
 * dans les mappings officiels) : {@code SaturationModule} a donc besoin de ce
 * signal ici aussi, pour ne pas voir son estimation de saturation prise en
 * défaut par un coup chargé.
 *
 * <p>Descripteur complet dans {@code method}, contrairement au mixin 26.1.2 :
 * sur une version obfusquée c'est la clé de l'entrée de refmap, et un nom seul
 * peut désigner plusieurs surcharges.
 */
@Mixin(targets = "net.minecraft.client.network.ClientPlayerInteractionManager")
public abstract class PiercingAttackMixin1211 {

    @Inject(method = "attackWithPiercingWeapon(Lnet/minecraft/component/type/PiercingWeaponComponent;)V",
            at = @At("TAIL"), require = 0)
    private void la$dispatchPiercingAttack(CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.PIERCING_ATTACK, null);
    }
}
