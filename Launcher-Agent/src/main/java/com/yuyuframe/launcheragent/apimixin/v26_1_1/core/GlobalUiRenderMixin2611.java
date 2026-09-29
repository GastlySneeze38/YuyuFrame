package com.yuyuframe.launcheragent.apimixin.v26_1_1.core;

import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiRenderMixin2611}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet — bugs trouvés, raisons des
 * différents ordres d'appel) — SEULE différence : utilise le pont apimixin
 * {@link GlobalUiRenderBridge2611} (même dossier, sans réflexion — voir sa
 * javadoc) au lieu de l'ancien pont réflexif. Reste "hub" volontairement PAS
 * dispatché via {@link com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry}
 * — c'est LUI le mécanisme par lequel les modules sont tickés/rendus, pas
 * quelque chose qu'un module observerait en plus (voir audit ROADMAP-agent.md
 * §3.3, jugement déjà noté pour ce fichier).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GlobalUiRenderMixin2611 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge2611.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge2611.inputPoller == null) {
                long handle = GlobalUiRenderBridge2611.getWindowHandle(mc);
                if (handle == 0L) return;
                GlobalUiRenderBridge2611.inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
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
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin2611 (apimixin): bootstrap() a levé: " + t);
                }
            }
            GlobalUiRenderBridge2611.inputPoller.poll();

            AgentBridge.get(this.getClass().getClassLoader()).tick();

            Object currentScreen = GlobalUiRenderBridge2611.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge2611.inputPoller.menuKeyPressed
                    && GlobalUiRenderBridge2611.isMouseGrabbed(mc)) {
                try {
                    Object menu = AgentBridge.get(this.getClass().getClassLoader()).mainMenuScreen();
                    if (menu != null) GlobalUiRenderBridge2611.setScreen(mc, menu);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin2611 (apimixin): setScreen(écran principal) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin2611 (apimixin): " + t);
        }
    }
}
