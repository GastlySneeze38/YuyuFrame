package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Migration de l'ancien {@code mixin.client.v26_1.GlobalUiPresentMixin263}
 * (supprimé le 2026-09-16 ; voir l'historique git pour l'historique complet) — SEULE différence : utilise
 * le pont apimixin {@link GlobalUiRenderBridge263} (même dossier) au lieu de
 * l'ancien pont réflexif. Reste "hub", même raisonnement que {@link
 * GlobalUiRenderMixin263} pour ne pas passer par {@code VanillaHookRegistry}.
 *
 * <p>26.2 : {@code RenderTarget.blitToScreen()} n'existe plus. La fin de frame
 * de {@code Minecraft.renderFrame} est désormais {@code GameRenderer.render}
 * → {@code GpuSurface.blitFromTexture(encoder, vueCouleurPrincipale)} →
 * {@code GpuSurface.present()} (relevé par javap). {@code blitFromTexture}
 * occupe exactement la place de l'ancien {@code blitToScreen} (qui faisait
 * {@code presentTexture} de la vue couleur principale) : mêmes points HEAD et
 * TAIL, même comportement que la 26.1.2 validée en jeu. Le contrôle « est-ce
 * la cible principale ? » porte maintenant sur la vue copiée — {@code this}
 * est la surface de la fenêtre, plus la cible de rendu.
 */
// 26.3 : GpuSurface est devenue une INTERFACE (api.device), blitFromTexture y
// est abstraite — rien à tisser. On vise son unique implémentation,
// FrontendGpuSurface, qui valide la copie puis la délègue au backend
// (GpuSurfaceBackend : GlSurface ou VulkanGpuSurface) — relevé par javap.
// Elle refuse la copie si l'encodeur est encore dans une passe : nos passes
// sont toutes fermées avant la fin de ce HEAD.
@Mixin(targets = "com.mojang.renderpearl.frontend.FrontendGpuSurface")
public abstract class GlobalUiPresentMixin263 {

    /**
     * TOUT se fait AVANT la copie vers la fenêtre — correctif du 2026-09-29
     * (interface invisible au premier test en jeu, HUD intact, aucune erreur).
     *
     * <p>En 26.1.2, {@code presentTexture} ne faisait que désigner la texture
     * à présenter ; la copie réelle avait lieu au {@code flipFrame}, APRÈS le
     * TAIL de {@code blitToScreen} — d'où un dessin de l'interface au TAIL qui
     * finissait quand même dans la frame. En 26.2, {@code blitFromTexture}
     * enregistre la copie sur-le-champ : un dessin au TAIL arrivait dans la
     * cible principale APRÈS la copie, puis était écrasé par la frame
     * suivante. Le log fichier le confirmait : chaîne de flou, atlas, icônes
     * du menu… tout était bien dessiné, simplement trop tard.
     *
     * <p>Ordre conservé de la 26.1.2 pour l'empilement : d'abord ce que la
     * frame a mis en file (dessous), puis l'interface, puis ce que
     * l'interface vient de mettre en file (texte, dessus). Effet de bord
     * heureux : l'interface ne traîne plus d'une frame.
     */
    @Inject(method = "blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", at = @At("HEAD"))
    private void la$onBeforeBlit(CommandEncoder encoder, GpuTextureView source, CallbackInfo ci) {
        Object mc;
        try {
            mc = GlobalUiRenderBridge263.getMcInstance();
            if (mc == null) return;
            if (!isMainColorView(mc, source)) return;
            Blaze3DCore.flushQueued();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin263 (apimixin, flush texte HEAD): " + t);
            return;
        }
        drawInterface(mc, this.getClass().getClassLoader());
        try {
            Blaze3DCore.flushQueued();
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin263 (apimixin, flush après interface): " + t);
        }
    }

    /** Overlay des modules ou écran de l'agent — l'ancien corps du TAIL de {@code blitToScreen} (26.1.2). */
    private static void drawInterface(Object mc, ClassLoader loader) {
        try {
            UiInputPoller inputPoller = GlobalUiRenderBridge263.inputPoller;
            if (inputPoller == null) return;

            Object currentScreen = GlobalUiRenderBridge263.getCurrentScreen(mc);
            UiRenderer renderer = UiRenderer.get(loader);

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
            AgentBridge agent = AgentBridge.get(loader);

            if (currentScreen == null) {
                if (!agent.hudHidden()) {
                    agent.renderOverlay(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
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

            // « Cet écran est-il un écran de l'agent, et demande-t-il à
            // naviguer ? » — la question est posée à la couche du dessus, qui
            // seule connaît UiScreenBase. Le setScreen/closeScreen, lui, reste
            // ici : c'est de la mécanique vanilla propre à ce bracket.
            if (agent.hasPendingNavigation(currentScreen)) {
                Object target = agent.consumePendingNavigation(currentScreen);
                if (target != null) GlobalUiRenderBridge263.setScreen(mc, target);
                else GlobalUiRenderBridge263.closeScreen(mc, currentScreen.getClass());
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiPresentMixin263 (apimixin): " + t);
        }
    }

    /**
     * 26.2 : équivalent de l'ancien {@code mainFramebuffer != this} — la vue
     * copiée vers la fenêtre est-elle la vue couleur de la cible principale ?
     */
    private static boolean isMainColorView(Object mc, GpuTextureView source) {
        Object main = GlobalUiRenderBridge263.getMainFramebuffer(mc);
        return main instanceof RenderTarget && ((RenderTarget) main).getColorTextureView() == source;
    }
}
