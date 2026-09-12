package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpu;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpus;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DText;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.GuiElementShaders;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.VanillaGuiSink;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.render.DrawContextStateBinding1211;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.texture.TextureSetup;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link VanillaGuiSink} pour la 1.21.11 — appels TYPÉS, aucune réflexion.
 *
 * <p>Compilée contre {@code src/stubs_1_21_11}, noms Yarn traduits au
 * chargement par {@code YarnNamedRemapper} : cette classe DOIT rester dans ce
 * paquet (voir {@code package-info}).
 *
 * <h2>Ce qui diffère de la 26.1.2</h2>
 * <ul>
 *   <li>Le hook transporte un {@code DrawContext} et non un
 *       {@code GuiGraphicsExtractor} ; son {@code GuiRenderState} est privé,
 *       capté à la construction (voir {@link DrawContextStateBinding1211}).</li>
 *   <li>Les éléments implémentent {@code SimpleGuiElementRenderState} et
 *       s'ajoutent par {@code addSimpleElement}.</li>
 *   <li>Le format de sommet maison se déclare directement : {@code
 *       VertexFormatElement} n'est pas obfusquée ici, donc pas d'accessor Mixin
 *       (contrairement à {@code VertexFormatElementAccessor261}).</li>
 * </ul>
 *
 * <h2>Portée de cette étape</h2>
 *
 * Fond arrondi et texte — c'est tout ce que le HUD pose réellement dans l'état
 * de GUI. Icône, vignette et verre dépoli répondent {@code false} : l'appelant
 * garde alors son chemin habituel (file Blaze3D pour les icônes, aplat pour le
 * verre), au lieu d'un HUD absent. Ils viendront avec leurs pipelines dédiés.
 */
public final class VanillaGuiSink1211 implements VanillaGuiSink {

    private RenderPipeline rectPipeline;
    private Object rectShaderSource;
    private RenderPipeline textPipeline;
    private Object textShaderSource;
    private boolean buildAttempted, buildFailed;

    /** Un {@code TextureSetup} par police — l'atlas ne change jamais de la session. */
    private final Map<UiFont, TextureSetup> fontSetups = new HashMap<>();

    private String lastReport;

    @Override
    public String id() {
        return "1.21.11";
    }

    /**
     * État de GUI porté par ce contexte, ou {@code null} si le mixin de capture
     * n'a pas été tissé.
     *
     * <p>Le cas nul est JOURNALISÉ (une fois) : sans lui, un mixin non tissé
     * ferait répondre {@code false} à chaque primitive et le HUD disparaîtrait
     * en silence — exactement le genre de panne muette que ce projet proscrit.
     */
    private GuiRenderState stateOf(Object hookContext) {
        Object state = DrawContextStateBinding1211.stateFor(hookContext);
        if (state instanceof GuiRenderState) return (GuiRenderState) state;
        reportOnce("état de GUI introuvable pour ce contexte — DrawContextStateMixin1211 tissé ?");
        return null;
    }

    @Override
    public boolean accepts(Object hookContext) {
        return stateOf(hookContext) != null;
    }

    @Override
    public int guiWidth(Object hookContext) {
        return hookContext instanceof DrawContext ? ((DrawContext) hookContext).getScaledWindowWidth() : -1;
    }

    @Override
    public int guiHeight(Object hookContext) {
        return hookContext instanceof DrawContext ? ((DrawContext) hookContext).getScaledWindowHeight() : -1;
    }

    /**
     * Pipelines construits UNE FOIS. Format de sommet MAISON dans les deux cas :
     * celui de {@code RenderPipelines.GUI} ne porte que Position + Color, sans
     * les attributs libres qu'exigent le SDF de coin et l'atlas de police (voir
     * {@link GuiElementShaders}).
     */
    private boolean buildPipelines() {
        if (buildAttempted) return !buildFailed;
        buildAttempted = true;
        try {
            Blaze3DGpu gpu = Blaze3DGpus.active();
            if (gpu == null) {
                buildFailed = true;
                LauncherLog.err("[VanillaGuiSink1211] aucune implémentation Blaze3DGpu");
                return false;
            }

            VertexFormat rectFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Color", VertexFormatElement.COLOR)
                .add("UV0", VertexFormatElement.UV0)
                .add("UV1", VertexFormatElement.UV1)
                .add("UV2", VertexFormatElement.UV2)
                .build();
            Object rectVsh = gpu.identifier("yuyuframe", "shader/ui_gui_rounded_rect.vsh");
            Object rectFsh = gpu.identifier("yuyuframe", "shader/ui_gui_rounded_rect.fsh");
            rectPipeline = (RenderPipeline) gpu.buildPipeline("ui_gui_rounded_rect", rectVsh, rectFsh,
                new String[0], new String[]{ "DynamicTransforms", "Projection" },
                RenderPipelines.GUI, rectFormat);
            rectShaderSource = gpu.shaderSource(rectVsh, GuiElementShaders.ROUNDED_RECT_VERTEX,
                rectFsh, GuiElementShaders.ROUNDED_RECT_FRAGMENT);

            VertexFormat textFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Color", VertexFormatElement.COLOR)
                .add("UV0", VertexFormatElement.UV0)
                .build();
            Object textVsh = gpu.identifier("yuyuframe", "shader/ui_gui_text.vsh");
            Object textFsh = gpu.identifier("yuyuframe", "shader/ui_gui_text.fsh");
            textPipeline = (RenderPipeline) gpu.buildPipeline("ui_gui_text", textVsh, textFsh,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                RenderPipelines.GUI, textFormat);
            textShaderSource = gpu.shaderSource(textVsh, GuiElementShaders.TEXT_VERTEX,
                textFsh, GuiElementShaders.TEXT_FRAGMENT);

            buildFailed = rectPipeline == null || textPipeline == null;
            if (buildFailed) LauncherLog.err("[VanillaGuiSink1211] pipeline nul après construction");
        } catch (Throwable t) {
            buildFailed = true;
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[VanillaGuiSink1211] construction des pipelines : " + t + " | cause réelle : " + cause);
        }
        return !buildFailed;
    }

    /**
     * Précompilé à CHAQUE passe, pas une seule fois : un rechargement de
     * ressources (F3+T) vide le cache de pipelines du device, et l'appel est un
     * no-op quand il y est déjà.
     */
    @Override
    public boolean ensureCompiled() {
        if (!buildPipelines()) return false;
        try {
            Blaze3DGpu gpu = Blaze3DGpus.active();
            if (gpu == null) return false;
            Object device = gpu.device();
            gpu.precompile(device, rectPipeline, rectShaderSource);
            gpu.precompile(device, textPipeline, textShaderSource);
            return true;
        } catch (Throwable t) {
            reportOnce("précompilation : " + t);
            return false;
        }
    }

    @Override
    public boolean roundedRect(Object hookContext, float x0, float y0, float x1, float y1,
                               float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                               UiColor color) {
        GuiRenderState state = stateOf(hookContext);
        if (state == null || !buildPipelines()) return false;
        try {
            state.addSimpleElement(new RoundedRectElement1211(x0, y0, x1, y1,
                rTopLeft, rTopRight, rBottomLeft, rBottomRight, argb(color), rectPipeline));
            return true;
        } catch (Throwable t) {
            reportOnce("roundedRect : " + t);
            return false;
        }
    }

    @Override
    public boolean text(Object hookContext, UiFont font, String content,
                        float x, float baselineY, float scale, UiColor color) {
        if (content == null || content.isEmpty()) return true;
        GuiRenderState state = stateOf(hookContext);
        if (state == null || !buildPipelines()) return false;
        try {
            TextureSetup atlas = fontSetup(font);
            if (atlas == null) {
                reportOnce("texte : atlas de police indisponible");
                return false;
            }
            state.addSimpleElement(new TextElement1211(font, content, x, baselineY, scale,
                argb(color), textPipeline, atlas));
            return true;
        } catch (Throwable t) {
            reportOnce("text : " + t);
            return false;
        }
    }

    /** L'atlas SDF du moteur, enveloppé pour vanilla — {@code Sampler0} du fragment. */
    private TextureSetup fontSetup(UiFont font) {
        TextureSetup cached = fontSetups.get(font);
        if (cached != null) return cached;
        Object[] entry = Blaze3DText.fontAtlasEntry(font);
        if (entry == null || entry.length < 3) return null;
        TextureSetup setup = TextureSetup.of((GpuTextureView) entry[1], (GpuSampler) entry[2]);
        fontSetups.put(font, setup);
        return setup;
    }

    @Override
    public boolean icon(Object hookContext, String cacheKey, BufferedImage img,
                        float x0, float y0, float x1, float y1, float alpha) {
        reportOnce("icône : pas encore portée sur 1.21.11 (repli sur la file Blaze3D)");
        return false;
    }

    @Override
    public boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                            float vSize, UiColor color) {
        reportOnce("vignette : pas encore portée sur 1.21.11");
        return false;
    }

    @Override
    public boolean supportsGlassPanel() {
        return false;
    }

    @Override
    public boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                              float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                              UiColor tint, UiColor background) {
        return false;
    }

    @Override
    public void nextStratum(Object hookContext) {
        GuiRenderState state = stateOf(hookContext);
        if (state == null) return;
        try {
            state.createNewRootLayer();
        } catch (Throwable t) {
            reportOnce("nextStratum : " + t);
        }
    }

    /**
     * Les icônes d'item vanilla ne sont pas portées dans l'état de GUI sur cette
     * version — elles passent par la file Blaze3D, vidée par
     * {@code GuiFlushMixin1211}. Rien à faire ici.
     */
    @Override
    public void flushItemIcons(Object hookContext) {
    }

    private static int argb(UiColor c) {
        return (Math.round(c.a * 255f) << 24)
             | (Math.round(c.r * 255f) << 16)
             | (Math.round(c.g * 255f) << 8)
             |  Math.round(c.b * 255f);
    }

    /** Journalise une raison d'échec UNE fois par raison distincte — jamais de sortie muette. */
    private void reportOnce(String message) {
        if (message.equals(lastReport)) return;
        lastReport = message;
        LauncherLog.err("[VanillaGuiSink1211] " + message);
    }
}
