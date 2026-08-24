package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BUG TROUVÉ ET CORRIGÉ (Aperçu shulker, fond de fenêtre de conteneur,
 * 1.21.4 — voir la javadoc complète de
 * {@link UiRenderer#drawVanillaContainerTextureModernImmediate}) : même
 * classe de bug que l'armure ({@link HudItemFlushMixin1214}) — un
 * {@code DrawContext} isolé construit hors du rendu vanilla ne fonctionne
 * jamais sur ce bracket — mais contrainte de z-order DIFFÉRENTE : le fond de
 * fenêtre doit apparaître PAR-DESSUS l'écran d'inventaire ouvert, donc se
 * dessiner APRÈS {@code Screen.render()}, pas seulement après {@code
 * InGameHud.render()} (qui s'exécute AVANT l'écran dans la frame — inutile
 * ici).
 *
 * BUG TROUVÉ ET CORRIGÉ (première version de ce Mixin, jamais testée en jeu
 * avant d'être corrigée par désassemblage) : hookait initialement TAIL de
 * {@code HandledScreen.drawForeground(DrawContext,I,I)} (obf "b",
 * method_2388) — compile et se tisse sans erreur, mais dessine dans le
 * VIDE : désassemblage bytecode de {@code HandledScreen.render(DrawContext,
 * I,I,F)} (obf "a", appelant direct de {@code drawForeground}) confirme que
 * cette dernière est appelée APRÈS un {@code context.getMatrices().push()} +
 * {@code translate(this.x, this.y, 0)} (champs propres au panneau du
 * conteneur, ex: coin haut-gauche de l'inventaire à l'écran) — {@code pop()}
 * n'intervient QUE tout à la fin de {@code render()} lui-même, après
 * plusieurs autres dessins (texte de durabilité/tooltip). Dessiner depuis le
 * TAIL de {@code drawForeground} place donc notre panneau en coordonnées
 * RELATIVES au coin du panneau ouvert au lieu des coordonnées GUI ABSOLUES
 * que {@code ShulkerPreviewModule.drawGrid} calcule — décalé silencieusement
 * hors de sa position attendue, sans exception (aucun log d'erreur possible
 * pour ce genre de bug, seul un test visuel en jeu le révèle).
 *
 * Correctif : hook déplacé sur {@code HandledScreen.render(DrawContext,I,I,
 * F)V} (obf "a", override direct de {@code Screen.render}, PAS hérité tel
 * quel — confirmé par désassemblage : cette classe déclare bien sa propre
 * implémentation) — TAIL, c-à-d APRÈS le {@code pop()} final, quand la
 * matrice est revenue en coordonnées écran absolues. DÉLIBÉRÉMENT sur
 * {@code HandledScreen} et PAS la classe {@code Screen} partagée par tous
 * les écrans (y compris nos écrans custom — voir la leçon ChatScreen/
 * HandledScreen de {@code ShulkerPreviewModule} sur ce risque précis).
 *
 * QUATRE paramètres cibles (context, mouseX, mouseY, delta) — TOUS capturés
 * en {@code @Coerce}/primitif dès qu'un seul l'est, par prudence (voir la
 * leçon {@code HudItemFlushMixin1214} : Sponge a déjà rejeté une capture
 * partielle sur un Mixin voisin de ce même bracket).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.HandledScreen")
public abstract class HandledScreenBlitFlushMixin1214 {

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V",
            at = @At("TAIL"), require = 0)
    private void la$flushPendingGuiBlits(@Coerce Object context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.get(this.getClass().getClassLoader()).flushPendingImmediateGuiBlits(context);
        } catch (Throwable t) {
            LauncherLog.err("[HandledScreenBlitFlushMixin1214] la$flushPendingGuiBlits: " + t);
        }
    }
}
