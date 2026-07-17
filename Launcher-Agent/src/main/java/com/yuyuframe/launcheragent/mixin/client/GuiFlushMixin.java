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
 * (armure/main de {@code ArmorDurabilityModule}, fond de conteneur de
 * {@code ShulkerPreviewModule} — voir la javadoc de UiRenderer
 * "Icône d'objet vanilla — pipeline MODERNE") dans l'état GuiRenderState
 * VIVANT de vanilla — {@code this} ici EST l'instance réelle de
 * GuiRenderState (fusionnée par Mixin), le MÊME objet que
 * GameRenderer.render() envoie ensuite au GPU un peu plus loin dans le frame
 * (GuiRenderer.render(GpuBufferSlice), tracé par désassemblage bytecode —
 * voir historique de session).
 *
 * BUG TROUVÉ ET CORRIGÉ (audit modules, cette session) — remplace l'ancien
 * hook HEAD sur {@code GameRenderer.render()} (icônes d'armure/durabilité
 * "probablement invisibles", voir module-bracket-audit.md) : une session
 * précédente avait tracé par bytecode (javap sur le jar client réel) que
 * {@code GameRenderer.render()} (obf {@code hob.a(Lgez;Z)V}) appelle {@code
 * this.F.e()} (champ "F"="guiState", méthode obf "e"), qui vide entièrement
 * GuiRenderState, JUSTE APRÈS la config lumière — donc APRÈS le point HEAD où
 * l'ancien hook ajoutait nos icônes. Toute icône mise en file à HEAD était
 * donc effacée avant le flush GPU réel, plus loin dans la même méthode.
 *
 * Cette session précédente n'avait pas corrigé le problème car "gqg" (le nom
 * obfusqué de GuiRenderState) semblait n'avoir aucune entrée Yarn "named"
 * connue — rendant risqué un {@code @At(INVOKE, target=...)} vers un nom
 * obfusqué brut (aurait cassé sous Fabric/intermediary, et pu faire échouer
 * TOUT le tissage de GameRenderer en cascade, voir la leçon ClearOverlaysMixin
 * dans module-bracket-audit.md). Vérification directe cette session dans
 * {@code mappings/mappings.tiny} : l'entrée existe bel et bien, juste sous un
 * chemin de package différent de celui essayé à l'époque —
 * {@code net/minecraft/client/gui/render/state/GuiRenderState}, méthode
 * official {@code e()V} == named {@code clear()V}, intermediary {@code
 * method_70926}. Cibler {@code clear()V} directement via
 * {@code @Inject(method=...)} se traduit alors normalement pour Fabric via le
 * refmap (voir REFMAP_ENTRIES dans LauncherMixinService), exactement comme
 * n'importe quelle autre entrée — plus besoin de deviner un nom obfusqué brut
 * ni de reproduire la chaîne d'appels imbriqués {@code this.k.i.t().a(...)}
 * utilisée par le hook équivalent 26.1.2 (voir GuiFlushMixin261).
 *
 * TAIL, pas HEAD : nos icônes doivent être ajoutées APRÈS que clear() ait fini
 * de vider l'état pour ce frame — sinon rien ne change par rapport au bug
 * ci-dessus. clear() peut être appelée plusieurs fois par frame sans risque :
 * {@link UiRenderer#flushPendingModernItemIconsFromState} vide la file une
 * seule fois (elle est déjà vide au second appel, no-op silencieux).
 *
 * BUG TROUVÉ (test utilisateur, 26.1.2) : {@code LinkageError: loader
 * constraint violation ... UiColor ... previously loaded by 'knot'} —
 * exactement le piège de classloader documenté dans la javadoc de
 * GlobalUiRenderMixin (Knot/'app'), mais réintroduit ICI : ce hook touchait
 * {@link UiRenderer} (et transitivement UiColor) potentiellement AVANT que
 * {@code FabricKnotExposer.ensureExposed()} n'ait eu la chance de tourner
 * (lui, câblé en TAIL sur GlobalUiRenderMixin) — chargement via 'app' ici,
 * puis via 'knot' plus tard ailleurs, définitions incompatibles de la MÊME
 * classe. Fix : appeler ensureExposed() ICI AUSSI, en tout premier —
 * idempotent, sans coût si déjà fait. Toujours nécessaire après le
 * changement de point d'accroche ci-dessus (même risque, seule la méthode
 * cible a changé).
 *
 * NON VÉRIFIÉ EN JEU (pas d'accès à un client 1.21.11 depuis cet
 * environnement) — voir les logs "[UiRenderer] itemIconModern" en cas
 * d'icône toujours invisible.
 */
@Mixin(targets = "net.minecraft.client.gui.render.state.GuiRenderState")
public abstract class GuiFlushMixin {

    @Inject(method = "clear()V", at = @At("TAIL"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIconsFromState(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin: " + t);
        }
    }
}
