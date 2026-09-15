package com.yuyuframe.launcheragent.apimixin.v1_8_9.input;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#SNEAK_KEY_HELD} sur 1.8.9 — {@code KeyboardInput.tick()V}.
 *
 * <p>La méthode lit six touches dans cet ordre (javap {@code bev.a()V}) :
 * avancer, reculer, gauche, droite, sauter, s'accroupir — d'où
 * {@code ordinal = 5}. Le résultat part dans {@code Input.sneaking}, seule
 * source de l'accroupissement du joueur.
 *
 * <p>Remplace l'ancien {@code MixinToggleSneak189}, qui écrivait le champ
 * {@code pressed} de la touche par réflexion puis le rétablissait en fin de
 * méthode. Ici seule la VALEUR LUE change : la touche garde son état réel.
 */
@Mixin(targets = "net.minecraft.client.input.KeyboardInput")
public abstract class SneakKeyHeldMixin189 {

    @ModifyExpressionValue(method = "tick()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z", ordinal = 5),
        require = 0)
    private boolean la$dispatchSneakHeld(boolean real) {
        Object held = VanillaHookRegistry.dispatchValue(HookPoint.SNEAK_KEY_HELD, Boolean.valueOf(real));
        return held instanceof Boolean ? (Boolean) held : real;
    }
}
