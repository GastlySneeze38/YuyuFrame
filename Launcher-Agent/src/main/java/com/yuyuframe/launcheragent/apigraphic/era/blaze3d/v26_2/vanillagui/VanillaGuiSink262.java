package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.vanillagui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaSlotSprite;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.vanillagui.pipeline.Blaze3DGuiRoundedRect;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.vanillagui.pipeline.Blaze3DGuiText;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiSink;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.GameRendererAccessor262;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.GuiGraphicsExtractorInvoker262;
import com.yuyuframe.launcheragent.apimixin.v26_2.core.GuiGraphicsExtractorAccessor262;
import org.joml.Matrix3x2fStack;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.GuiRendererAccessor262;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * {@link VanillaGuiSink} de la 26.1.2 — délègue à {@link VanillaGuiLayer},
 * inchangée.
 *
 * <p>26.2 (2026-09-29) : copie de {@code v26_1_2/vanillagui/VanillaGuiSink261}
 * branchée sur les accessors {@code apimixin/v26_2} ; les changements de
 * Blaze3D sont absorbés par {@code Blaze3DGpu262} et les pipelines de ce
 * dossier (audits à zéro). Déclarée par {@link VanillaGuiSinkProvider262}.
 *
 * <p>Aucune ligne de rendu n'a été réécrite en introduisant l'interface : ce
 * fichier ne fait que donner une FORME commune au chemin déjà validé en jeu,
 * pour que la 1.21.11 puisse en fournir un pendant. Même précaution que lors de
 * l'introduction de {@code Blaze3DGpu}.
 *
 * <p>Sur une autre version, {@link #accepts} répond {@code false} : le contexte
 * du hook n'y est pas un {@code GuiGraphicsExtractor}, {@link VanillaGuiLayer}
 * le détecte et le journalise une fois.
 */
public final class VanillaGuiSink262 implements VanillaGuiSink {

    @Override
    public String id() {
        return "26.2";
    }

    @Override
    public boolean accepts(Object hookContext) {
        return VanillaGuiLayer.isAvailable(hookContext);
    }

    @Override
    public int guiWidth(Object hookContext) {
        return VanillaGuiLayer.guiWidth(hookContext);
    }

    @Override
    public boolean ensureCompiled() {
        // `|` et non `||` : les DEUX doivent être tentés, sinon un échec du
        // premier empêcherait le second de se compiler pour toujours.
        boolean rect = Blaze3DGuiRoundedRect.ensureCompiled();
        boolean text = Blaze3DGuiText.ensureCompiled();
        return rect && text;
    }

    @Override
    public boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                               float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                               UiColor color) {
        return VanillaGuiLayer.roundedRect(hookContext, x0, y0, x1, y1,
            rTopLeft, rTopRight, rBottomLeft, rBottomRight, color);
    }

    @Override
    public boolean text(Object hookContext, UiFont font, String content,
                        float x, float baselineY, float scale, UiColor color) {
        return VanillaGuiLayer.text(hookContext, font, content, x, baselineY, scale, color);
    }

    @Override
    public boolean icon(Object hookContext, String cacheKey, BufferedImage img,
                        float x0, float y0, float x1, float y1, float alpha) {
        return VanillaGuiLayer.icon(hookContext, cacheKey, img, x0, y0, x1, y1, alpha);
    }

    @Override
    public boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                            float vSize, UiColor color) {
        return VanillaGuiLayer.vignette(hookContext, x0, y0, x1, y1, vSize, color);
    }

    @Override
    public boolean supportsGlassPanel() {
        return true;
    }

    @Override
    public boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                              float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                              UiColor tint, UiColor background) {
        return VanillaGuiLayer.glassPanel(hookContext, x0, y0, x1, y1,
            rTopLeft, rTopRight, rBottomLeft, rBottomRight, tint, background);
    }

    @Override
    public void flushItemIcons(Object hookContext) {
        VanillaGuiLayer.flushItemIcons(hookContext);
    }

    // ── Pont des icônes d'objet — typé, sans réflexion (2026-09-13) ───────
    //
    // Les trois hôtes sont servis. Les champs privés (GameRenderer.guiRenderer,
    // GuiRenderer.renderState) et le constructeur passent par les accessors et
    // l'invoker 26.1.2 déjà tissés, écrits pour ce pont et jamais branchés
    // jusqu'ici ; tout le reste est public.

    @Override
    public Object guiState(VanillaFlushHost host, Object hostObject) {
        switch (host) {
            case GUI_STATE:
                return hostObject;
            case GUI_RENDERER:
                return hostObject instanceof GuiRendererAccessor262
                    ? ((GuiRendererAccessor262) hostObject).la$renderState() : null;
            case GAME_RENDERER: {
                if (!(hostObject instanceof GameRendererAccessor262)) return null;
                Object guiRenderer = ((GameRendererAccessor262) hostObject).la$guiRenderer();
                return guiRenderer instanceof GuiRendererAccessor262
                    ? ((GuiRendererAccessor262) guiRenderer).la$renderState() : null;
            }
            default:
                return null; // hôtes de l'ère gl3, jamais émis sur cette version
        }
    }

    @Override
    public void drawVanillaItems(Object guiState, List<VanillaItemIcon> icons, List<VanillaGuiBlit> blits) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !(guiState instanceof GuiRenderState)) return;
        GuiGraphicsExtractor g = GuiGraphicsExtractorInvoker262.la$new(mc, (GuiRenderState) guiState, 0, 0);
        RenderPipeline textured = RenderPipelines.GUI_TEXTURED;

        for (VanillaGuiBlit blit : blits) {
            g.blit(textured, Identifier.withDefaultNamespace(blit.texturePath),
                blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                Math.round(blit.texW), Math.round(blit.texH));
        }

        Identifier slot = null;
        for (VanillaItemIcon icon : icons) {
            if (!(icon.itemStack instanceof ItemStack)) continue;
            ItemStack stack = (ItemStack) icon.itemStack;
            // Taille demandée ≠ 16 pixels GUI : icône (et case/barre) mises à
            // l'échelle par la pose, à la position exacte — voir VanillaItemIcon.scale.
            Matrix3x2fStack pose = icon.scaled() ? ((GuiGraphicsExtractorAccessor262) (Object) g).la$pose() : null;
            int ox = icon.guiX, oy = icon.guiY;
            if (pose != null) {
                pose.pushMatrix();
                pose.translate(icon.guiXExact, icon.guiYExact);
                pose.scale(icon.scale, icon.scale);
                ox = 0;
                oy = 0;
            }
            if (icon.vanillaExtras) {
                if (slot == null) slot = Identifier.withDefaultNamespace(VanillaSlotSprite.SPRITE);
                g.blitSprite(textured, slot, ox - VanillaSlotSprite.ICON_DX,
                    oy - VanillaSlotSprite.ICON_DY, VanillaSlotSprite.W, VanillaSlotSprite.H);
            }
            g.item(stack, ox, oy);
            if (icon.vanillaExtras) g.itemDecorations(mc.font, stack, ox, oy);
            if (pose != null) pose.popMatrix();
        }
    }
}
