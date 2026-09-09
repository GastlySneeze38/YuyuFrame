package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.apigraphic.core.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiPresentMixin261}
 * (voir ce fichier pour l'historique complet) — SEULE différence : utilise
 * le pont apimixin {@link GlobalUiRenderBridge261} (même dossier) au lieu de
 * l'ancien pont réflexif. Reste "hub", même raisonnement que {@link
 * GlobalUiRenderMixin261} pour ne pas passer par {@code VanillaHookRegistry}.
 */
@Mixin(targets = "com.mojang.blaze3d.pipeline.RenderTarget")
public abstract class GlobalUiPresentMixin261 {

    @Inject(method = "blitToScreen()V", at = @At("HEAD"))
    private void la$onBeforeBlit(CallbackInfo ci) {
        try {
            Object mc = GlobalUiRenderBridge261.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge261.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return;
            Blaze3DCore.flushQueued();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin261 (apimixin, flush texte HEAD): " + t);
        }
    }

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void la$onAfterBlit(CallbackInfo ci) {
        try {
            UiInputPoller inputPoller = GlobalUiRenderBridge261.inputPoller;
            if (inputPoller == null) return;

            Object mc = GlobalUiRenderBridge261.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge261.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return;

            Object currentScreen = GlobalUiRenderBridge261.getCurrentScreen(mc);
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
            if (currentScreen == null) {
                if (!HudOverlayRenderer.vanillaHudHidden()) {
                    ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
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

            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) GlobalUiRenderBridge261.setScreen(mc, target);
                    else GlobalUiRenderBridge261.closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin261 (apimixin): " + t);
        }
    }
}
