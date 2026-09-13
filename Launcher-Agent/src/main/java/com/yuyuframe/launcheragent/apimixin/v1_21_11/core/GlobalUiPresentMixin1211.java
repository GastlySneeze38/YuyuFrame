package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hub de DESSIN du moteur UI sur 1.21.11, sur {@code Framebuffer.blitToScreen()}
 * — portage apimixin de {@code mixin.client.GlobalUiPresentMixin} (voir ce
 * fichier pour le pourquoi du double hook HEAD/TAIL : texte Blaze3D à flusher
 * AVANT la présentation, dessin GL brut APRÈS).
 *
 * <h2>Le HUD n'est plus dessiné ici — volontairement</h2>
 *
 * L'original dessinait le HUD (et sa variante « écran ouvert ») à cet endroit,
 * APRÈS la présentation de la frame : il passait donc forcément par-dessus
 * toute la GUI vanilla, chat compris. La 26.1.2 a abandonné ce chemin au profit
 * de l'émission DANS l'état de GUI vanilla depuis {@code HUD_EXTRACT_CHAT}
 * (voir {@code docs/LauncherAgent/rendering-pipeline.md}), et la 1.21.11 a
 * exactement le même mécanisme ({@code GuiRenderState}, {@code InGameHud.renderChat}).
 *
 * <p>Ce fichier suit donc {@code GlobalUiPresentMixin261} à l'identique : le HUD
 * de cette tranche arrivera par le même chemin qu'en 26.1.2, à l'étape du
 * portage qui branche {@code HUD_EXTRACT_CHAT} sur 1.21.11. D'ici là la tranche
 * n'a pas de HUD — c'est un manque temporaire assumé, pas un oubli : le
 * réintroduire ici ferait revenir le bug de z-order que la refonte a réglé.
 *
 * <p>Restent ici, comme en 26.1.2 : les overlays plein écran des modules, et
 * nos propres écrans.
 */
@Mixin(targets = "net.minecraft.client.gl.Framebuffer")
public abstract class GlobalUiPresentMixin1211 {

    @Inject(method = "blitToScreen()V", at = @At("HEAD"))
    private void la$onBeforeBlit(CallbackInfo ci) {
        try {
            Object mc = GlobalUiRenderBridge1211.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge1211.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return; // même garde que la TAIL.
            Blaze3DCore.flushQueued();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin1211 (flush texte HEAD): " + t);
        }
    }

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void la$onAfterBlit(CallbackInfo ci) {
        try {
            UiInputPoller inputPoller = GlobalUiRenderBridge1211.inputPoller;
            if (inputPoller == null) return; // 1ère(s) frame(s), avant l'init du hub logique.

            Object mc = GlobalUiRenderBridge1211.getMcInstance();
            if (mc == null) return;
            Object mainFramebuffer = GlobalUiRenderBridge1211.getMainFramebuffer(mc);
            if (mainFramebuffer != this) return; // pas le framebuffer principal.

            Object currentScreen = GlobalUiRenderBridge1211.getCurrentScreen(mc);
            UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
            AgentBridge agent = AgentBridge.get(this.getClass().getClassLoader());

            if (currentScreen == null) {
                if (!agent.hudHidden()) {
                    agent.renderOverlay(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                return;
            }

            if (!(currentScreen instanceof UiDrawable)) return;

            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

            if (agent.hasPendingNavigation(currentScreen)) {
                Object target = agent.consumePendingNavigation(currentScreen);
                if (target != null) GlobalUiRenderBridge1211.setScreen(mc, target);
                else GlobalUiRenderBridge1211.closeScreen(mc, currentScreen.getClass());
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin1211: " + t);
        }
    }
}
