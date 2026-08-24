package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.ShulkerPreviewModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.apigraphic.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.UiTextBlaze3D;
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
            UiTextBlaze3D.flushQueued();
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

            if (currentScreen == null) {
                if (!HudOverlayRenderer.vanillaHudHidden()) {
                    HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                    ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                return;
            }

            if (!(currentScreen instanceof UiDrawable)) {
                HudOverlayRenderer.renderPersistent(renderer, currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
                ShulkerPreviewModule.renderIfApplicable(renderer, currentScreen, inputPoller, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }

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
