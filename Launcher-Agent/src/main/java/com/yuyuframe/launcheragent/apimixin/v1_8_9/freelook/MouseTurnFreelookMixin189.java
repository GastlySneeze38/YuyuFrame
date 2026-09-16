package com.yuyuframe.launcheragent.apimixin.v1_8_9.freelook;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@link HookPoint#FREELOOK_TURN_INTERCEPT} sur 1.8.9 — même point que
 * {@code MouseHandlerFreelookMixin261}/{@code MouseHandlerFreelookMixin1211}.
 *
 * <pre>
 *   26.1.2  : MouseHandler.turnPlayer(D)  → LocalPlayer.turn(DD)
 *   1.21.11 : Mouse.updateMouse(D)        → ClientPlayerEntity.changeLookDirection(DD)
 *   1.8.9   : GameRenderer.render(FJ)     → ClientPlayerEntity.increaseTransforms(FF)
 * </pre>
 *
 * En 1.8.9 la souris est lue dans le rendu : javap de {@code bfk.a(FJ)V},
 * DEUX appels à {@code bew.c(FF)V} (caméra lissée, offset 327 ; normale, 358).
 * Le {@code @Redirect} sans ordinal couvre les deux. Les deltas y ont déjà
 * traversé la courbe de sensibilité vanilla, et {@code increaseTransforms}
 * applique le même facteur 0,15 que {@code turn} — le module les traite donc
 * exactement comme sur les autres versions.
 *
 * <p>{@code @Redirect} + {@link Coerce} sur le receveur, pour la même raison
 * qu'en 1.21.11 (MixinExtras refuse un receveur {@code Object}) : l'appel est
 * remplacé, on le refait via {@link EntityRotationAccessor189} quand le
 * freelook ne l'intercepte pas. Méthode héritée d'{@code Entity}, traduite par
 * le repli « méthode héritée » de {@code REFMAP_REMAP}.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class MouseTurnFreelookMixin189 {

    @Redirect(method = "render(FJ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/entity/player/ClientPlayerEntity;increaseTransforms(FF)V"),
            require = 0)
    private void la$interceptTurn(@Coerce Object player, float yaw, float pitch) {
        if (!VanillaHookRegistry.dispatch(HookPoint.FREELOOK_TURN_INTERCEPT, new double[]{yaw, pitch})) {
            ((EntityRotationAccessor189) player).la$increaseTransforms(yaw, pitch);
        }
    }
}
