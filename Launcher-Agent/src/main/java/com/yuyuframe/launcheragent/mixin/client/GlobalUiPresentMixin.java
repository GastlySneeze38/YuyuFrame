package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Point d'accroche du DESSIN réel de notre moteur UI custom sur le bracket
 * 1.21.11 (Blaze3D) — voir la javadoc de {@link GlobalUiRenderMixin} pour le
 * pourquoi complet (era E, bug de composition Blaze3D) : dessiner en GL brut
 * à la TAIL de GameRenderer.render() se fait écraser par
 * Framebuffer.blitToScreen(), appelé juste après par MinecraftClient.render
 * (confirmé par désassemblage bytecode). Ce Mixin cible directement
 * Framebuffer.blitToScreen() en TAIL — donc APRÈS que Blaze3D ait fini de
 * composer sa frame sur le framebuffer par défaut, mais AVANT
 * Window.swapBuffers() (plus loin dans MinecraftClient.render) — le seul
 * moment où un dessin GL brut survit jusqu'à l'affichage.
 *
 * Garde : blitToScreen() n'est vérifiée QUE sur le Framebuffer PRINCIPAL
 * (MinecraftClient.getFramebuffer()) — aucun autre appelant connu à ce jour,
 * mais son nom Yarn générique ne garantit pas qu'aucun mod tiers ne l'appelle
 * sur une autre instance (ex: rendu hors-écran) ; sans cette garde, on
 * dessinerait potentiellement notre UI sur le mauvais framebuffer, ou
 * plusieurs fois par frame.
 *
 * État (écran actif, input déjà pollé) partagé avec {@link GlobalUiRenderMixin}
 * via {@link GlobalUiRenderBridge} — GlobalUiRenderMixin (GameRenderer.render,
 * exécuté PLUS TÔT dans la même frame) s'occupe de la logique (poll input,
 * tick, ouverture du menu), ce Mixin ne fait QUE lire cet état et dessiner.
 */
@Mixin(targets = "net.minecraft.client.gl.Framebuffer")
public abstract class GlobalUiPresentMixin {

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void la$onAfterBlit(CallbackInfo ci) {
        try {
            UiInputPoller inputPoller = GlobalUiRenderBridge.inputPoller;
            if (inputPoller == null) return; // 1ère frame(s), avant l'init côté GlobalUiRenderMixin.

            Object mc = GlobalUiRenderBridge.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return; // pas le framebuffer principal — voir javadoc de classe.

            Object currentScreen = GlobalUiRenderBridge.getCurrentScreen(mc);
            UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());

            if (currentScreen == null) {
                // Overlay HUD permanent — même règle que le HUD vanilla
                // (hotbar/vie), qui ne s'affiche pas non plus quand un écran
                // est ouvert. Pendant l'édition (UiHudEditorScreen), ce sont
                // les UiHudBox de cet écran qui dessinent, pas cet appel.
                HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }

            if (GlobalUiRenderBridge.DIAG_LOGGED_CLASSES.add(currentScreen.getClass())) {
                LauncherLog.info("[LauncherAgent] DIAG4: currentScreen=" + currentScreen
                    + " class=" + currentScreen.getClass() + " isUiDrawable=" + (currentScreen instanceof UiDrawable));
            }

            if (!(currentScreen instanceof UiDrawable)) {
                // Écran NON custom ouvert (chat, inventaire, tout autre GUI
                // vanilla/mod) — seuls les éléments HUD marqués
                // showWhenScreenOpen restent visibles (voir HudElement, réglage
                // générique façon OneConfig).
                HudOverlayRenderer.renderPersistent(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }

            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

            // Navigation demandée par l'écran lui-même (UiScreenBase.closeTo,
            // ex: clic sur une carte de mod ou bouton retour) — appliquée ICI,
            // seul endroit qui connaît déjà mc + setScreen correctement résolus
            // pour cette version (voir UiScreenBase.closeTo pour le pourquoi).
            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) GlobalUiRenderBridge.setScreen(mc, target);
                    else GlobalUiRenderBridge.closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin: " + t);
        }
    }
}
