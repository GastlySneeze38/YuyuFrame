package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.apigraphic.shader.UiSolidPipelinePoc;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.command.ClientCommandRegistry;
import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
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
                // Audit du catalogue statique — placé ICI parce que c'est le
                // premier instant où TOUS les enregistrements ont eu lieu
                // (modules ci-dessus + ClientCommandRegistry juste avant).
                // Voir VanillaHookRegistry.auditDeclarations : sans lui, les
                // déclarations LauncherModule.hookPoints dérivent en silence,
                // et une gate qui s'appuierait dessus écarterait des mixins
                // pourtant nécessaires.
                try {
                    VanillaHookRegistry.auditDeclarations(ModuleRegistry.declaredHookPoints());
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): audit HookPoint a levé: " + t);
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

            // Preuve de mécanisme "pipeline shader maison" (roadmap Phase 5,
            // voir ShaderPipelineFactory) — inerte par défaut, /yf shaderpoc
            // pour activer. Try/catch dédié : un échec ici ne doit jamais
            // affecter le reste du hub de rendu.
            if (UiSolidPipelinePoc.testEnabled) {
                try {
                    UiSolidPipelinePoc.drawTestQuad();
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): UiSolidPipelinePoc.drawTestQuad() a levé: " + t);
                }
            }

            // Preuve de mécanisme flou dual-Kawase (roadmap Phase 5.1) — voir
            // Blaze3DBlur, /yf blurpoc pour activer. fbWidth/fbHeight déjà
            // résolus par l'input poller (même source que ModuleRegistry
            // .renderOverlayAll, voir GlobalUiPresentMixin261), pas besoin de
            // re-résoudre GLFW ici.
            if (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlur.testEnabled) {
                try {
                    com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlur.drawTestPanel(
                        GlobalUiRenderBridge261.inputPoller.fbWidth, GlobalUiRenderBridge261.inputPoller.fbHeight);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): Blaze3DBlur.drawTestPanel() a levé: " + t);
                }
            }

            // Preuve de mécanisme rects batchés (roadmap Phase 5.5) — voir
            // Blaze3DRect, /yf batchpoc pour activer.
            if (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DRect.batchTestEnabled) {
                try {
                    com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DRect.drawTestBatch(
                        GlobalUiRenderBridge261.inputPoller.fbWidth, GlobalUiRenderBridge261.inputPoller.fbHeight);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): Blaze3DRect.drawTestBatch() a levé: " + t);
                }
            }

            // Preuve de mécanisme rich text (roadmap Phase 5.3) — voir
            // UiRichText, /yf richtextpoc pour activer.
            if (com.yuyuframe.launcheragent.apigraphic.core.UiRichText.testEnabled) {
                try {
                    com.yuyuframe.launcheragent.apigraphic.UiRenderer richRenderer =
                        com.yuyuframe.launcheragent.apigraphic.UiRenderer.get(this.getClass().getClassLoader());
                    com.yuyuframe.launcheragent.apigraphic.core.UiRichText.drawTestParagraph(richRenderer,
                        GlobalUiRenderBridge261.inputPoller.mouseX, GlobalUiRenderBridge261.inputPoller.mouseY,
                        GlobalUiRenderBridge261.inputPoller.fbWidth, GlobalUiRenderBridge261.inputPoller.fbHeight);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): UiRichText.drawTestParagraph() a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): " + t);
        }
    }
}
