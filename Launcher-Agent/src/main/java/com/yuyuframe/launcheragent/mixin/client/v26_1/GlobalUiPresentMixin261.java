package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiTextBlaze3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code GlobalUiPresentMixin}, javadoc de
 * tête pour le pourquoi complet — bug de composition Blaze3D era E) pour MC
 * 26.1+ — {@code net.minecraft.client.gl.Framebuffer} devient {@code
 * com.mojang.blaze3d.pipeline.RenderTarget} (changement de PACKAGE en plus du
 * nom), mais {@code blitToScreen()} garde EXACTEMENT le même nom — vérifié
 * via {@code javap} sur le jar client 26.1.2 réel :
 * {@code public void blitToScreen();}. Aucune autre différence de
 * comportement attendue (même logique de garde "framebuffer principal
 * seulement", même partage d'état via {@link GlobalUiRenderBridge261}).
 *
 * Vérifié en jeu (menu/HUD custom fonctionnels sur 26.1.2).
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
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin261 (flush texte HEAD): " + t);
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
                HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }

            if (GlobalUiRenderBridge261.DIAG_LOGGED_CLASSES.add(currentScreen.getClass())) {
                LauncherLog.info("[LauncherAgent] DIAG4 (26.1): currentScreen=" + currentScreen
                    + " class=" + currentScreen.getClass() + " isUiDrawable=" + (currentScreen instanceof UiDrawable));
            }

            if (!(currentScreen instanceof UiDrawable)) {
                HudOverlayRenderer.renderPersistent(renderer, currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
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
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin261: " + t);
        }
    }
}
