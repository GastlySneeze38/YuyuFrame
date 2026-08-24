package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Soumet les icônes d'objet vanilla mises en file par {@link UiRenderer}
 * (armure/main de {@code ArmorDurabilityModule}, fond de conteneur de
 * {@code ShulkerPreviewModule}) dans le {@code GuiRenderState} VIVANT de
 * vanilla — {@code this} ici EST l'instance réelle de {@code GuiRenderer}
 * (fusionnée par Mixin).
 *
 * BUG TROUVÉ ET CORRIGÉ #2 (test utilisateur, 1.21.11 : panneau shulker
 * enfin visible mais DERRIÈRE l'écran d'inventaire — mauvais z-order) —
 * remplace le hook précédent (TAIL de {@code GuiRenderState.clear()V}, voir
 * {@link UiRenderer#flushPendingModernItemIconsFromGuiRenderer} pour la
 * trace bytecode complète justifiant le nouveau point d'accroche) : {@code
 * clear()} est appelé TRÈS TÔT dans {@code GameRenderer.render()V}, AVANT
 * {@code InGameHud.render(...)V} ET {@code Screen.render(...)V} — nos
 * icônes/fond y étaient donc ajoutés EN PREMIER, dessinés EN PREMIER, donc
 * DERRIÈRE tout le reste. Ce Mixin cible maintenant directement {@code
 * GuiRenderer.render(GpuBufferSlice)V} — la soumission GPU réelle, appelée
 * APRÈS que HUD ET écran ouvert aient fini d'ajouter leur contenu à l'état
 * partagé — nos icônes/fond, ajoutés ici en DERNIER, se retrouvent dessinés
 * PAR-DESSUS, z-order correct.
 *
 * {@code GpuBufferSlice} (paramètre du target) n'est PAS capturé — package
 * {@code com.mojang.blaze3d.buffers} jamais obfusqué (bibliothèque externe
 * partagée, même nom réel sur tous les brackets), mais capturer un
 * paramètre typé n'est de toute façon jamais nécessaire ici (seul le TIMING
 * de l'appel compte) — voir la leçon {@code ClearOverlaysMixin}/{@code
 * @Coerce} dans module-bracket-audit.md : ne jamais capturer un paramètre
 * du target sans en avoir besoin, même quand son type semble sûr.
 */
@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer")
public abstract class GuiFlushMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
    private void la$flushPendingItemIcons(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            UiRenderer.flushPendingModernItemIconsFromGuiRenderer(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GuiFlushMixin: " + t);
        }
    }
}
