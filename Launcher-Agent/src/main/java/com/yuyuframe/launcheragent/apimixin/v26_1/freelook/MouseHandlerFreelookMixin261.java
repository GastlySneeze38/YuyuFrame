package com.yuyuframe.launcheragent.apimixin.v26_1.freelook;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Équivalent apimixin de l'ancien {@code mixin.client.v26_1.MouseHandlerFreelookMixin261}
 * (voir ce fichier pour l'historique complet — notamment le BUG "2 tours en
 * freelook contre un demi-tour en F5", causé par une reconstruction MANUELLE
 * de la courbe de sensibilité vanilla ; le mixin/ historique reste actif et
 * inchangé, ceci est construit en parallèle, non branché).
 *
 * Remplace l'annulation totale de {@code turnPlayer(D)V} + lecture réflexion
 * de {@code accumulatedDX}/{@code accumulatedDY} + reconstruction manuelle
 * de la courbe de sensibilité par {@code @WrapWithCondition} (MixinExtras)
 * sur l'appel {@code LocalPlayer.turn(D,D)} DANS {@code turnPlayer} —
 * technique reprise de Omnilook ({@code dev.rdh.omnilook.mixin.fabric.
 * MouseHandlerMixin}, référence externe déjà étudiée, voir audit
 * ROADMAP-agent.md §3.3).
 *
 * ZÉRO réflexion ET zéro reconstruction de courbe : {@code turnPlayer()}
 * s'exécute ENTIÈREMENT en vanilla (lecture/reset de accumulatedDX/DY,
 * courbe de sensibilité — TOUT ça reste interne à Minecraft, jamais touché
 * ici) — on n'intercepte que le TOUT DERNIER appel, {@code
 * player.turn(yRot, xRot)}, où {@code yRot}/{@code xRot} sont déjà les
 * deltas PLEINEMENT calculés par vanilla (post-courbe de sensibilité) : en
 * les redirigeant vers {@link FreelookModule#accumulate}, on obtient une
 * fidélité bit-à-bit à F5 par construction, plus aucun risque de facteur
 * ×4/×6.7 mal calculé à la main.
 *
 * {@code @WrapWithCondition} : si le handler retourne {@code false}, l'appel
 * réel à {@code player.turn(...)} est ANNULÉ (remplace {@code ci.cancel()}
 * de l'ancienne version) ; {@code true} le laisse s'exécuter normalement
 * (le joueur tourne, comportement vanilla inchangé quand le freelook n'est
 * pas engagé).
 *
 * NON TESTÉ EN JEU au moment de l'écriture.
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
public abstract class MouseHandlerFreelookMixin261 {

    @WrapWithCondition(method = "turnPlayer(D)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean la$interceptTurn(LocalPlayer instance, double yRot, double xRot) {
        if (!FreelookModule.isFreelookEngaged()) {
            FreelookModule.deactivate();
            return true;
        }
        FreelookModule.accumulate(yRot, xRot);
        return false;
    }
}
