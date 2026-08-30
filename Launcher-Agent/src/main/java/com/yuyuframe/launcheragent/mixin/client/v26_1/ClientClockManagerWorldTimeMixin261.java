package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.visual.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Port de {@code MixinWorldTime189} (1.8.9) pour 26.1.2 — voir historique de
 * session pour le premier essai (patch du résultat FINAL de
 * {@code SkyRenderer.extractRenderState}, abandonné) : demande explicite de
 * l'utilisateur derrière ce module — pouvoir tester le rendu (shaders) à
 * N'IMPORTE QUELLE heure INSTANTANÉMENT, sans attendre le vrai cycle
 * jour/nuit, en PUREMENT client-side (jamais toucher au vrai temps serveur).
 * Patcher {@code sunAngle}/{@code moonAngle}/{@code starAngle} individuellement
 * ne suffisait qu'à déplacer soleil/lune — la couleur du ciel/l'assombrissement
 * ambiant (ce dont un shader a besoin pour un test utile) restaient pilotés
 * par le VRAI temps, chacun via son propre "EnvironmentAttribute" séparé.
 *
 * Fix : remonte à LA SOURCE UNIQUE dont TOUS ces attributs dérivent —
 * {@code ClientClockManager.getTotalTicks(Holder)J} (confirmé par
 * désassemblage bytecode du vrai jar 26.1.2 : simple lookup d'un
 * {@code ClockInstance.totalTicks} par horloge — c'est CE nombre que le
 * système EnvironmentAttribute lit pour calculer sunAngle/skyColor/
 * assombrissement/etc., pas une valeur séparée par attribut). Renvoyer
 * NOTRE heure ICI, à la source, fait cascader le changement à TOUT ce qui
 * en dépend — soleil/lune ET couleur du ciel/éclairage ambiant — sans avoir
 * à comprendre/reproduire individuellement chaque attribut.
 *
 * {@code ClientClockManager} (PAS {@code ServerClockManager}) — par
 * construction/nommage, cette classe n'existe QUE côté client, jamais
 * synchronisée vers le serveur : répond directement à l'exigence "que du
 * client side" (le vrai temps serveur, lu par {@code ServerClockManager},
 * n'est jamais touché — spawn de mobs, cycles de lune réels, etc. inchangés).
 */
// BUG TROUVÉ (crash en jeu à la connexion, "InvalidInjectionException:
// Invalid descriptor... Expected (Lnet/minecraft/core/Holder;...) but found
// (Ljava/lang/Object;...)") : contrairement à d'autres Mixins de ce projet
// qui omettent simplement les paramètres non capturés, Sponge Mixin exige
// ICI une correspondance EXACTE de descripteur pour tout paramètre capturé
// AVANT le CallbackInfo(Returnable) — "Object" à la place du vrai type
// {@code net.minecraft.core.Holder} ne passe PAS la validation (contrairement
// à ce qu'on pourrait attendre, "Object" n'est PAS toujours accepté comme
// substitut universel). Fix : nouveau stub compile-only {@code
// net.minecraft.core.Holder} (src/stubs), paramètre retypé en conséquence.
@Mixin(targets = "net.minecraft.client.ClientClockManager")
public abstract class ClientClockManagerWorldTimeMixin261 {

    // Diag TEMPORAIRE (retiré une fois confirmé fonctionnel en jeu) — un seul
    // log, au premier appel APRÈS activation du module, pour confirmer que
    // ce hook est bien atteint par le pipeline de rendu (retour utilisateur :
    // "ça ne marche pas", cause encore inconnue — soit ce hook n'est jamais
    // appelé pour le rendu du ciel, soit il l'est mais sans effet visible).
    private static boolean diagLogged;

    @Inject(method = "getTotalTicks(Lnet/minecraft/core/Holder;)J", at = @At("HEAD"), cancellable = true)
    private void la$fakeTotalTicks(Holder holder, CallbackInfoReturnable<Long> cir) {
        try {
            LauncherModule moduleBase = ModuleRegistry.get("world-time");
            if (!(moduleBase instanceof WorldTimeModule) || !moduleBase.isEnabled()) return;
            WorldTimeModule module = (WorldTimeModule) moduleBase;

            if (!diagLogged) {
                diagLogged = true;
                LauncherLog.info("[ClientClockManagerWorldTimeMixin261] diag: hook atteint, holder=" + holder
                    + " valeur renvoyée=" + module.time);
            }

            cir.setReturnValue((long) module.time);
        } catch (Throwable t) {
            LauncherLog.err("[ClientClockManagerWorldTimeMixin261] la$fakeTotalTicks: " + t);
        }
    }
}
