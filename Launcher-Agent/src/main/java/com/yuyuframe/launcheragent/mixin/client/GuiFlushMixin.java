package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Soumet les icônes d'objet vanilla mises en file par {@link UiRenderer}
 * (armure/main de {@code ArmorDurabilityModule}, voir sa javadoc dans
 * UiRenderer "Icône d'objet vanilla — pipeline MODERNE") dans l'état
 * GuiRenderState VIVANT de vanilla — {@code this} ici EST l'instance réelle
 * de GameRenderer (fusionnée par Mixin), donc {@code this.guiRenderer.state}
 * est le MÊME objet que GameRenderer.render() va lui-même envoyer au GPU un
 * peu plus loin dans cette même méthode (GuiRenderer.render(GpuBufferSlice),
 * tracé par désassemblage bytecode — voir historique de session).
 *
 * HEAD, pas TAIL (contrairement à GlobalUiRenderMixin, qui gère la logique du
 * moteur UI custom et n'a pas cette contrainte) : le flush réel a lieu QUELQUE
 * PART DANS cette méthode, donc TAIL serait TROP TARD.
 *
 * Mixin distinct de GlobalUiRenderMixin (même méthode cible, classe séparée)
 * pour ne pas mélanger deux responsabilités sans rapport (logique moteur UI
 * vs participation au pipeline de rendu vanilla) — Sponge Mixin supporte
 * nativement plusieurs @Mixin sur la même méthode cible.
 *
 * BUG TROUVÉ (test utilisateur, 26.1.2) : {@code LinkageError: loader
 * constraint violation ... UiColor ... previously loaded by 'knot'} —
 * exactement le piège de classloader documenté dans la javadoc de
 * GlobalUiRenderMixin (Knot/'app'), mais réintroduit ICI : HEAD s'exécute
 * TOUJOURS avant TAIL dans un même appel de méthode, donc au tout premier
 * frame, ce hook touchait {@link UiRenderer} (et transitivement UiColor)
 * AVANT que {@code FabricKnotExposer.ensureExposed()} n'ait eu la chance de
 * tourner (lui, câblé en TAIL sur GlobalUiRenderMixin) — chargement via 'app'
 * ici, puis via 'knot' plus tard ailleurs, définitions incompatibles de la
 * MÊME classe. Fix : appeler ensureExposed() ICI AUSSI, en tout premier —
 * idempotent, sans coût si déjà fait.
 *
 * PROBLÈME CONNU, NON CORRIGÉ ICI (contrairement au bracket 26.1.2, voir
 * {@link com.yuyuframe.launcheragent.mixin.client.v26_1.GuiFlushMixin261}
 * pour le correctif équivalent déjà appliqué là-bas) : trace bytecode
 * complète de {@code hob.a(Lgez;Z)V} (le VRAI render() 1.21.11, javap sur le
 * jar client réel) montre, contrairement à 26.1.2, un appel {@code
 * this.F.e()} (GuiRenderState.reset(), champ "F"="guiState") juste APRÈS la
 * config lumière ({@code this.k.i.t().a(fyd$a.c)}, équivalent de
 * DiffuseLighting.setupFor), donc DANS render() lui-même — HEAD est donc
 * ANTÉRIEUR à ce reset, et toute icône ajoutée ici est VRAISEMBLABLEMENT
 * EFFACÉE avant le flush plus loin dans la méthode. Non corrigé pour l'instant
 * car {@code gqg} (GuiRenderState) N'A AUCUNE entrée Yarn "named" (seulement
 * official/intermediary — voir YarnMappings/le bug de classe "gqg" trouvé
 * plus tôt dans l'historique de session), donc aucun moyen sûr/déjà éprouvé
 * dans ce projet d'écrire un {@code @At(INVOKE, target=...)} vers {@code
 * gqg.e()V} qui fonctionne à la fois en vanilla brut ET sous Fabric
 * (intermediary) sans risquer de faire échouer TOUTE la config Mixin au
 * chargement (voir historique : incident MixinWorldTime116/LunarWorldView).
 * RÉSULTAT ATTENDU ACTUELLEMENT SUR CE BRACKET : icônes probablement TOUJOURS
 * INVISIBLES malgré la file d'attente qui se vide sans erreur — NON VÉRIFIÉ
 * EN JEU (pas d'accès à un client 1.21.11 depuis cet environnement, et
 * l'utilisateur teste sur 26.1.2, déjà corrigé).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GuiFlushMixin {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIcons(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin: " + t);
        }
    }
}
