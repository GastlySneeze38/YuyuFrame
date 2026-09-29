package com.yuyuframe.launcheragent.apimixin.v26_2.core;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiPresentMixin262}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet) — SEULE différence : utilise
 * le pont apimixin {@link GlobalUiRenderBridge262} (même dossier) au lieu de
 * l'ancien pont réflexif. Reste "hub", même raisonnement que {@link
 * GlobalUiRenderMixin262} pour ne pas passer par {@code VanillaHookRegistry}.
 */
@Mixin(targets = "com.mojang.blaze3d.pipeline.RenderTarget")
public abstract class GlobalUiPresentMixin262 {

    @Inject(method = "blitToScreen()V", at = @At("HEAD"))
    private void la$onBeforeBlit(CallbackInfo ci) {
        try {
            Object mc = GlobalUiRenderBridge262.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge262.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return;
            Blaze3DCore.flushQueued();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin262 (apimixin, flush texte HEAD): " + t);
        }
    }

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void la$onAfterBlit(CallbackInfo ci) {
        try {
            UiInputPoller inputPoller = GlobalUiRenderBridge262.inputPoller;
            if (inputPoller == null) return;

            Object mc = GlobalUiRenderBridge262.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge262.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return;

            Object currentScreen = GlobalUiRenderBridge262.getCurrentScreen(mc);
            UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());

            // LE HUD N'EST PLUS DESSINÉ ICI sur ce bracket (2026-08-30) — il
            // est émis pendant la passe GUI de vanilla, depuis
            // HUD_EXTRACT_CHAT (voir HudOverlayRenderer.renderInVanillaGui et
            // docs/LauncherAgent/rendering-pipeline.md). Ce point-ci se situe
            // APRÈS la présentation de la frame : tout ce qu'on y dessinait
            // passait forcément par-dessus la GUI vanilla, chat compris, sans
            // aucun moyen de s'intercaler.
            //
            // Restent ici les rendus PAS ENCORE portés, qui gardent donc
            // l'ancien comportement : l'overlay plein écran des modules
            // (teinte vie basse) et l'aperçu shulker.
            AgentBridge agent = AgentBridge.get(this.getClass().getClassLoader());

            if (currentScreen == null) {
                if (!agent.hudHidden()) {
                    agent.renderOverlay(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                return;
            }

            // Écran vanilla/mod ouvert : plus rien à dessiner ici depuis la
            // suppression de l'aperçu shulker (2026-08-31) — le HUD, lui, part
            // de la passe GUI, pas d'ici.
            if (!(currentScreen instanceof UiDrawable)) return;

            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

            // « Cet écran est-il un écran de l'agent, et demande-t-il à
            // naviguer ? » — la question est posée à la couche du dessus, qui
            // seule connaît UiScreenBase. Le setScreen/closeScreen, lui, reste
            // ici : c'est de la mécanique vanilla propre à ce bracket.
            if (agent.hasPendingNavigation(currentScreen)) {
                Object target = agent.consumePendingNavigation(currentScreen);
                if (target != null) GlobalUiRenderBridge262.setScreen(mc, target);
                else GlobalUiRenderBridge262.closeScreen(mc, currentScreen.getClass());
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin262 (apimixin): " + t);
        }
    }
}
