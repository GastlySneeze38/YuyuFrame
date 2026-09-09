package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.apigraphic.shader.UiSolidPipelinePoc;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
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
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): bootstrap() a levé: " + t);
                }
            }
            GlobalUiRenderBridge261.inputPoller.poll();

            AgentBridge.get(this.getClass().getClassLoader()).tick();

            Object currentScreen = GlobalUiRenderBridge261.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge261.inputPoller.menuKeyPressed
                    && GlobalUiRenderBridge261.isMouseGrabbed(mc)) {
                try {
                    Object menu = AgentBridge.get(this.getClass().getClassLoader()).mainMenuScreen();
                    if (menu != null) GlobalUiRenderBridge261.setScreen(mc, menu);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): setScreen(écran principal) a levé: " + t);
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

            // Preuve de mécanisme particules (roadmap Phase 5.4) — voir
            // UiParticleSystem, /yf particlepoc pour activer.
            if (com.yuyuframe.launcheragent.apigraphic.core.UiParticleSystem.testEnabled) {
                try {
                    com.yuyuframe.launcheragent.apigraphic.UiRenderer particleRenderer =
                        com.yuyuframe.launcheragent.apigraphic.UiRenderer.get(this.getClass().getClassLoader());
                    com.yuyuframe.launcheragent.apigraphic.core.UiParticleSystem.tickAndDrawTest(particleRenderer,
                        GlobalUiRenderBridge261.inputPoller.fbWidth, GlobalUiRenderBridge261.inputPoller.fbHeight);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): UiParticleSystem.tickAndDrawTest() a levé: " + t);
                }
            }

            // Preuve de mécanisme modes de fusion (roadmap Phase 5.4) — voir
            // Blaze3DBlend, /yf blendpoc pour activer.
            if (com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlend.testEnabled) {
                try {
                    com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DBlend.drawTestPanels(
                        GlobalUiRenderBridge261.inputPoller.fbWidth, GlobalUiRenderBridge261.inputPoller.fbHeight);
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin261 (apimixin): Blaze3DBlend.drawTestPanels() a levé: " + t);
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
