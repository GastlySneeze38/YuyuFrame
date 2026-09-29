package com.yuyuframe.launcheragent.apimixin.v26_1_1.freelook;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Équivalent apimixin de l'ancien {@code mixin.client.v26_1.MouseHandlerFreelookMixin2611}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — notamment le BUG "2 tours en
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
 * Passe par {@link HookPoint#FREELOOK_TURN_INTERCEPT} (audit ROADMAP-agent.md
 * §4 — apimixin ne doit jamais importer un module concret de {@code
 * runtime.*}, ce fichier importait directement {@code FreelookModule} avant
 * cette correction) — {@link VanillaHookRegistry#dispatch} renvoie {@code
 * true} quand {@code FreelookModule} a consommé le delta (accumulé côté
 * caméra), auquel cas le tour réel du joueur doit être annulé.
 *
 * ⚠️ Aller-retour {@code @WrapWithCondition} → {@code @Redirect} →
 * {@code @WrapWithCondition} (2026-08-25, §14) — ne pas « corriger » sans
 * lire ceci. Le handler n'était jamais invoqué, SANS la moindre erreur, et on
 * a d'abord cru à un problème de retransform ; la vraie cause est que
 * MixinExtras n'était pas initialisé dans NOTRE environnement Mixin, donc le
 * cœur de Sponge recopiait {@code la$interceptTurn} dans la classe cible sans
 * jamais l'appeler. Corrigé dans {@code IsolatedBootstrap.initMixinExtras()}
 * (voir sa javadoc). Le passage temporaire en {@code @Redirect} avait servi
 * de contournement et prouvé le diagnostic.
 *
 * Retour à {@code @WrapWithCondition} (l'approche d'Omnilook) une fois
 * MixinExtras opérationnel, pour la COMPOSABILITÉ : deux {@code @Redirect}
 * concurrents sur un même site d'appel s'excluent mutuellement, alors que
 * plusieurs conditions MixinExtras cohabitent. On tourne à côté de ~89 mods,
 * dont {@code freecam}, qui a toutes les raisons de toucher
 * {@code MouseHandler} — un {@code @Redirect} y serait une bombe à retardement.
 *
 * ⚠️ Pourquoi on ne peut PAS appliquer ici le remède de
 * {@code HudExtractCrosshairMixin2611} (viser la méthode appelée plutôt que le
 * site d'appel, immunisé contre les enveloppements) : intercepter
 * {@code LocalPlayer.turn} PRÉCISÉMENT à ce site d'appel est porteur de sens —
 * c'est ce qui donne des deltas déjà passés par la courbe de sensibilité
 * vanilla, donc une fidélité au bit près à F5. Injecter dans
 * {@code turnPlayer} lui-même ferait resurgir le bug historique du facteur
 * ×6.7 (courbe reconstruite à la main). Et un mixin sur {@code LocalPlayer}/
 * {@code Entity} reste exclu (hiérarchie commune à toutes les entités, voir
 * la javadoc de {@code FreelookModule}).
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
public abstract class MouseHandlerFreelookMixin2611 {

    @WrapWithCondition(method = "turnPlayer(D)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean la$interceptTurn(LocalPlayer instance, double yRot, double xRot) {
        return !VanillaHookRegistry.dispatch(HookPoint.FREELOOK_TURN_INTERCEPT, new double[]{yRot, xRot});
    }
}
