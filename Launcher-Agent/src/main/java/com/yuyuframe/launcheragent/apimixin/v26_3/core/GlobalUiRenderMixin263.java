package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.v26_3.input.SdlNativeInput263;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiRenderMixin263}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — bugs trouvés, raisons des
 * différents ordres d'appel) — SEULE différence : utilise le pont apimixin
 * {@link GlobalUiRenderBridge263} (même dossier, sans réflexion — voir sa
 * javadoc) au lieu de l'ancien pont réflexif. Reste "hub" volontairement PAS
 * dispatché via {@link com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry}
 * — c'est LUI le mécanisme par lequel les modules sont tickés/rendus, pas
 * quelque chose qu'un module observerait en plus (voir audit ROADMAP-agent.md
 * §3.3, jugement déjà noté pour ce fichier).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GlobalUiRenderMixin263 {

    // 26.3 : GameRenderer.render() ne prend plus d'arguments (javap).
    @Inject(method = "render()V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge263.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge263.inputPoller == null) {
                long handle = GlobalUiRenderBridge263.getWindowHandle(mc);
                if (handle == 0L) return;
                // 26.3 : SDL3, plus de GLFW — le poller lit l'état par
                // SdlNativeInput263 (handle = SDL_Window*) et reçoit les
                // événements des mixins input/ de cette tranche.
                GlobalUiRenderBridge263.inputPoller = new UiInputPollerModern(
                    new SdlNativeInput263(handle, mc), this.getClass().getClassLoader());
                // Tout ce bloc vivait ICI en dur (registre de modules, réglages
                // globaux, commandes client, audit des HookPoint) — huit
                // imports de runtime/ depuis apimixin/, donc un cycle de
                // couches. Passé derrière AgentBridge, résolu PAR NOM depuis
                // le classloader du code tissé : la propriété qui justifiait
                // ces appels ici (charger runtime/ depuis le classloader du
                // jeu, jamais celui du système — LinkageError sinon) est
                // conservée à l'identique, la référence de compilation
                // disparaît. Voir AgentBridge.
                try {
                    AgentBridge.get(this.getClass().getClassLoader()).bootstrap();
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin263 (apimixin): bootstrap() a levé: " + t);
                }
            }
            GlobalUiRenderBridge263.inputPoller.poll();

            AgentBridge.get(this.getClass().getClassLoader()).tick();

            Object currentScreen = GlobalUiRenderBridge263.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge263.inputPoller.menuKeyPressed
                    && GlobalUiRenderBridge263.isMouseGrabbed(mc)) {
                try {
                    Object menu = AgentBridge.get(this.getClass().getClassLoader()).mainMenuScreen();
                    if (menu != null) GlobalUiRenderBridge263.setScreen(mc, menu);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin263 (apimixin): setScreen(écran principal) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin263 (apimixin): " + t);
        }
    }
}
