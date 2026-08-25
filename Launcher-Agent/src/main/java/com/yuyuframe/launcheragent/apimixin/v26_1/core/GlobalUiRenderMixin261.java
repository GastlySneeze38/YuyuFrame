package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.runtime.command.ClientCommandRegistry;
import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.apigraphic.UiInputPollerModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiRenderMixin261}
 * (voir ce fichier pour l'historique complet — bugs trouvés, raisons des
 * différents ordres d'appel) — SEULE différence : utilise le pont apimixin
 * {@link GlobalUiRenderBridge261} (même dossier, sans réflexion — voir sa
 * javadoc) au lieu de l'ancien pont réflexif. Reste "hub" volontairement PAS
 * dispatché via {@link com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry}
 * — c'est LUI le mécanisme par lequel les modules sont tickés/rendus, pas
 * quelque chose qu'un module observerait en plus (voir audit ROADMAP-agent.md
 * §3.3, jugement déjà noté pour ce fichier).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GlobalUiRenderMixin261 {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge261.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge261.inputPoller == null) {
                long handle = GlobalUiRenderBridge261.getWindowHandle(mc);
                if (handle == 0L) return;
                GlobalUiRenderBridge261.inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
                ModuleRegistry.all();
                GlobalUiSettings.INSTANCE.onConfigChanged();
                // Phase 4.5 — déplacé depuis LauncherAgent.premain0() (voir sa
                // javadoc et [[project_mc_261_port]] §10) : DOIT être touché
                // depuis ICI (classloader du jeu, comme ModuleRegistry juste
                // au-dessus), jamais depuis premain0() (classloader système),
                // sous peine de LinkageError sur VanillaHookRegistry.
                try {
                    ClientCommandRegistry.bootstrap();
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): ClientCommandRegistry.bootstrap() a levé: " + t);
                }
            }
            GlobalUiRenderBridge261.inputPoller.poll();

            try {
                ModuleRegistry.tickAll();
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): ModuleRegistry.tickAll() a levé: " + t);
            }

            Object currentScreen = GlobalUiRenderBridge261.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge261.inputPoller.menuKeyPressed
                    && GlobalUiRenderBridge261.isMouseGrabbed(mc)) {
                try {
                    GlobalUiRenderBridge261.setScreen(mc, new UiMainMenuScreen(null));
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): setScreen(UiMainMenuScreen) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): " + t);
        }
    }
}
