package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hub LOGIQUE du moteur UI sur 1.21.11 — input, tick des modules, ouverture du
 * menu. Portage apimixin de {@code mixin.client.GlobalUiRenderMixin} (voir ce
 * fichier pour l'historique : pourquoi la logique est ici et le dessin sur
 * {@code Framebuffer.blitToScreen}, pourquoi {@code MinecraftClient} ne peut
 * pas être ciblé dans les gros modpacks).
 *
 * <p>Même structure que {@code GlobalUiRenderMixin261} : tout ce que l'original
 * appelait directement dans {@code runtime/} ({@code ModuleRegistry},
 * {@code GlobalUiSettings}, {@code UiMainMenuScreen}) passe désormais par
 * {@link AgentBridge}. {@code bootstrap()} en fait plutôt plus que l'original
 * (commandes client, audit des HookPoint) — ce qui manque encore à cette
 * tranche pour s'en servir (mixin {@code CHAT_SEND}) se contente d'être inerte.
 *
 * <p>Conservé de l'original : l'ouverture du menu ne teste PAS
 * {@code isMouseGrabbed} (test propre au pont 26.1.2) — même comportement
 * qu'avant le gel de la tranche.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin1211 {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            // DOIT rester la première instruction — voir l'original pour le
            // pourquoi (double définition de nos classes par APP puis Knot,
            // LinkageError, menu qui ne s'ouvrait jamais).
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge1211.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge1211.inputPoller == null) {
                long handle = GlobalUiRenderBridge1211.getWindowHandle(mc);
                if (handle == 0L) return;
                GlobalUiRenderBridge1211.inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
                try {
                    AgentBridge.get(this.getClass().getClassLoader()).bootstrap();
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1211: bootstrap() a levé: " + t);
                }
            }
            GlobalUiRenderBridge1211.inputPoller.poll();

            AgentBridge.get(this.getClass().getClassLoader()).tick();

            Object currentScreen = GlobalUiRenderBridge1211.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge1211.inputPoller.menuKeyPressed) {
                try {
                    Object menu = AgentBridge.get(this.getClass().getClassLoader()).mainMenuScreen();
                    if (menu != null) GlobalUiRenderBridge1211.setScreen(mc, menu);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1211: setScreen(écran principal) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1211: " + t);
        }
    }
}
