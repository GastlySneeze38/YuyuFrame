package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.OldItemRotationsModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Old item rotations" — inspiré d'OverflowAnimations/Animatium-Legacy
 * (repo GitHub Polyfrost/OverflowAnimationsV2,
 * {@code MixinItemRenderer.animatium$removeRotations}). Leur version utilise
 * {@code @Redirect} sur l'APPEL à {@code rotateWithPlayerRotations(EntityPlayerSP,F)}
 * pour le remplacer par un no-op — IMPOSSIBLE ici (voir MixinToggleSprint189/
 * MixinToggleSneak189 pour l'historique complet : @Redirect avec des
 * paramètres typés MC est rejeté par Sponge Mixin dans ce bootstrap custom,
 * "expected X" peu importe le stub essayé). On obtient le MÊME résultat en
 * ciblant directement la MÉTHODE APPELÉE ({@code applyPlayerRotation}, lettre
 * officielle "a", descripteur "(Lbew;F)V" — confirmé dans mappings-1.8.9.tiny
 * ET par javap sur bfn.class = HeldItemRenderer) et en l'annulant à la
 * volée (@Inject cancellable en HEAD) quand le module est actif — équivalent
 * fonctionnel exact (l'appelant continue normalement, seul le corps de CETTE
 * méthode ne s'exécute pas), sans passer par @Redirect du tout.
 *
 * CORRECTIF : première version déclarait {@code Object player} dans la
 * signature du handler pour capturer le 1er paramètre de la cible — REJETÉ
 * en jeu : {@code InvalidInjectionException ... Expected (Lbew;F...)V but
 * found (Ljava/lang/Object;F...)V}. Contrairement à ce qu'on pensait, @Inject
 * exige aussi une correspondance EXACTE de type pour les paramètres CAPTURÉS
 * de la méthode cible (même règle stricte que @Redirect, voir javadoc
 * MixinToggleSprint189) — la solution n'est PAS un stub, mais de ne capturer
 * AUCUN des paramètres d'origine (on ne s'en sert pas ici de toute façon) :
 * @Inject accepte un handler qui ne déclare QUE le CallbackInfo, sans avoir à
 * lister les paramètres réels de la cible — c'est le pattern déjà utilisé
 * partout ailleurs dans ce projet (MixinToggleSprint189/MixinToggleSneak189
 * en HEAD/TAIL, etc.).
 */
@Mixin(targets = "net.minecraft.client.render.item.HeldItemRenderer")
public abstract class MixinOldItemRotations189 {

    @Inject(method = "a(Lbew;F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void la$noItemRotation(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("old-item-rotations");
            if (module instanceof OldItemRotationsModule && module.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[MixinOldItemRotations189] la$noItemRotation: " + t);
        }
    }
}
