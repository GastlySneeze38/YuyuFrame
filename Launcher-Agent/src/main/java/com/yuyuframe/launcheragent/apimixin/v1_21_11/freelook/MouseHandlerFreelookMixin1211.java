package com.yuyuframe.launcheragent.apimixin.v1_21_11.freelook;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#FREELOOK_TURN_INTERCEPT} sur 1.21.11 — pendant EXACT de
 * {@code MouseHandlerFreelookMixin261} : même injecteur, même point.
 *
 * <pre>
 *   26.1.2 : MouseHandler.turnPlayer(D)  → LocalPlayer.turn(DD)
 *   1.21.11 : Mouse.updateMouse(D)        → ClientPlayerEntity.changeLookDirection(DD)
 * </pre>
 *
 * Même raison qu'en 26.1.2 de viser CE site d'appel : les deltas y ont déjà
 * traversé la courbe de sensibilité vanilla, d'où une fidélité au bit près à
 * F5 — l'ancien {@code mixin.client.MouseHandlerFreelookMixin} reconstruisait
 * cette courbe à la main.
 *
 * <h2>Deux points propres à cette version</h2>
 *
 * <ul>
 *   <li><b>Méthode héritée.</b> Le bytecode appelle
 *       {@code invokevirtual ClientPlayerEntity.changeLookDirection}, méthode
 *       DÉCLARÉE sur {@code Entity}. Yarn ne la connaît que sous
 *       {@code Entity} : c'est le repli « méthode héritée » de
 *       {@code MappingsRegistry.mapMethodName} qui la traduit, via
 *       {@code REFMAP_REMAP}.</li>
 *   <li><b>Receveur typé {@code Object}.</b> Le vrai type est obfusqué et nos
 *       stubs ne lui correspondent pas. MixinExtras ne gère pas {@code @Coerce}
 *       (aucune référence dans son bytecode), et on n'y a trouvé aucune
 *       vérification de signature non plus — d'où ce pari, isolé sur ce seul
 *       mixin : s'il était refusé, le log le dirait ici, la caméra
 *       ({@link CameraFreelookMixin1211}, cœur de Mixin seul) resterait
 *       indépendante, et le repli serait un {@code @Redirect} avec
 *       {@code @Coerce}, que le cœur de Mixin gère.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.Mouse")
public abstract class MouseHandlerFreelookMixin1211 {

    @WrapWithCondition(method = "updateMouse(D)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"),
            require = 0)
    private boolean la$interceptTurn(Object player, double yRot, double xRot) {
        return !VanillaHookRegistry.dispatch(HookPoint.FREELOOK_TURN_INTERCEPT, new double[]{yRot, xRot});
    }
}
