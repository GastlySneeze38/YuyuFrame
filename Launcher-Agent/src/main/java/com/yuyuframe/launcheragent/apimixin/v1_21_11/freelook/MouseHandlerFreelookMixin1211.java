package com.yuyuframe.launcheragent.apimixin.v1_21_11.freelook;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@link HookPoint#FREELOOK_TURN_INTERCEPT} sur 1.21.11 — même point que
 * {@code MouseHandlerFreelookMixin261}, injecteur différent.
 *
 * <pre>
 *   26.1.2 : MouseHandler.turnPlayer(D)  → LocalPlayer.turn(DD)
 *   1.21.11 : Mouse.updateMouse(D)        → ClientPlayerEntity.changeLookDirection(DD)
 * </pre>
 *
 * Même raison qu'en 26.1.2 de viser CE site d'appel : les deltas y ont déjà
 * traversé la courbe de sensibilité vanilla (fidélité au bit près à F5).
 *
 * <h2>Pourquoi {@code @Redirect} et pas {@code @WrapWithCondition}</h2>
 *
 * Testé en jeu (v1057) : MixinExtras VALIDE la signature du handler et refuse
 * un receveur {@code Object} — « Found unexpected argument type
 * java.lang.Object at index 0, expected net.minecraft.class_746 ». Le type
 * réel est obfusqué, on ne peut pas l'écrire. Le cœur de Mixin, lui, accepte
 * {@link Coerce} sur le receveur d'un {@code @Redirect}. Contrepartie : le
 * {@code @Redirect} REMPLACE l'appel, on le refait donc via
 * {@link EntityInvoker1211} quand le freelook ne l'intercepte pas. Un
 * {@code @Redirect} ne compose pas avec un autre sur le même appel — risque
 * accepté, aucun mod de ce modpack ne le vise.
 *
 * <p>Méthode héritée : {@code changeLookDirection} n'est déclarée dans Yarn
 * que sur {@code Entity} — traduite par le repli « méthode héritée » de
 * {@code MappingsRegistry.mapMethodName} via {@code REFMAP_REMAP} (confirmé
 * dans le log v1057 : « résolue comme méthode héritée → method_5872 »).
 */
@Mixin(targets = "net.minecraft.client.Mouse")
public abstract class MouseHandlerFreelookMixin1211 {

    @Redirect(method = "updateMouse(D)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"),
            require = 0)
    private void la$interceptTurn(@Coerce Object player, double yRot, double xRot) {
        if (!VanillaHookRegistry.dispatch(HookPoint.FREELOOK_TURN_INTERCEPT, new double[]{yRot, xRot})) {
            ((EntityInvoker1211) player).la$changeLookDirection(yRot, xRot);
        }
    }
}
