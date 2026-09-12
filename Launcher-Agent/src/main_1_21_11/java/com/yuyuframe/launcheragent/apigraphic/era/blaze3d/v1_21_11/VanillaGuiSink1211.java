package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpu;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpus;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DRect;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DText;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.GuiElementShaders;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
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
 * <h2>Primitives couvertes</h2>
 *
 * Fond arrondi, texte, icône, vignette et verre dépoli — parité avec la
 * 26.1.2 depuis le 2026-09-12.
 *
 * <p>Fond et texte sont CRITIQUES : leur échec éteint la sink entière. Les
 * trois autres sont construites à part et peuvent manquer individuellement
 * sans emporter le HUD (voir {@code buildExtras}) — c'est la même garantie que
 * la 26.1.2 obtient en les compilant à la demande.
 *
 * <p>Ce qui est PARTAGÉ avec la 26.1.2 plutôt que recopié : tout le GLSL
 * ({@code GuiElementShaders}), l'atlas d'icônes ({@code Blaze3DRect}) et la
 * chaîne de flou ({@code Blaze3DBlur}) — ces trois-là passent déjà par
 * {@link Blaze3DGpu}, donc par l'implémentation de la version active. Seule la
 * soumission à l'état de GUI est propre à cette tranche.
 */
public final class VanillaGuiSink1211 implements VanillaGuiSink {

    private RenderPipeline rectPipeline;
    private Object rectShaderSource;
    private RenderPipeline textPipeline;
    private Object textShaderSource;
    private RenderPipeline iconPipeline;
    private Object iconShaderSource;
    private RenderPipeline vignettePipeline;
    private Object vignetteShaderSource;
    private RenderPipeline glassPipeline;
    private Object glassShaderSource;
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
            if (buildFailed) {
                LauncherLog.err("[VanillaGuiSink1211] pipeline nul après construction");
                return false;
            }
        } catch (Throwable t) {
            buildFailed = true;
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[VanillaGuiSink1211] construction des pipelines : " + t + " | cause réelle : " + cause);
            return false;
        }
        buildExtras();
        return true;
    }

    /**
     * Icône, vignette et verre — construits dans la MÊME passe que le fond et
     * le texte, mais dans un try/catch à part et SANS toucher
     * {@code buildFailed}.
     *
     * <p>C'est la règle que la 26.1.2 obtenait en les compilant à la demande :
     * un échec sur l'une de ces trois primitives ne doit pas faire disparaître
     * le HUD ENTIER. Chacune vérifie son propre pipeline avant de s'en servir
     * et répond {@code false} toute seule, ce qui rend à l'appelant son chemin
     * de repli.
     */
    private void buildExtras() {
        try {
            Blaze3DGpu gpu = Blaze3DGpus.active();
            if (gpu == null) return;

            // Icône : MÊME format que le texte — seul le fragment change (voir
            // GuiElementShaders). Le pipeline, lui, doit être distinct : c'est
            // lui, avec la texture, qui décide du regroupement en maillages.
            VertexFormat iconFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Color", VertexFormatElement.COLOR)
                .add("UV0", VertexFormatElement.UV0)
                .build();
            Object iconVsh = gpu.identifier("yuyuframe", "shader/ui_gui_icon.vsh");
            Object iconFsh = gpu.identifier("yuyuframe", "shader/ui_gui_icon.fsh");
            iconPipeline = (RenderPipeline) gpu.buildPipeline("ui_gui_icon", iconVsh, iconFsh,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                RenderPipelines.GUI, iconFormat);
            iconShaderSource = gpu.shaderSource(iconVsh, GuiElementShaders.ICON_VERTEX,
                iconFsh, GuiElementShaders.ICON_FRAGMENT);

            // Vignette et verre : même format que le rect arrondi (position
            // locale, demi-taille, deux entiers libres), deux lectures
            // différentes de ces entiers côté fragment.
            VertexFormat shapeFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Color", VertexFormatElement.COLOR)
                .add("UV0", VertexFormatElement.UV0)
                .add("UV1", VertexFormatElement.UV1)
                .add("UV2", VertexFormatElement.UV2)
                .build();

            Object vignetteVsh = gpu.identifier("yuyuframe", "shader/ui_gui_vignette.vsh");
            Object vignetteFsh = gpu.identifier("yuyuframe", "shader/ui_gui_vignette.fsh");
            vignettePipeline = (RenderPipeline) gpu.buildPipeline("ui_gui_vignette", vignetteVsh, vignetteFsh,
                new String[0], new String[]{ "DynamicTransforms", "Projection" },
                RenderPipelines.GUI, shapeFormat);
            vignetteShaderSource = gpu.shaderSource(vignetteVsh, GuiElementShaders.VIGNETTE_VERTEX,
                vignetteFsh, GuiElementShaders.VIGNETTE_FRAGMENT);

            Object glassVsh = gpu.identifier("yuyuframe", "shader/ui_gui_glass.vsh");
            Object glassFsh = gpu.identifier("yuyuframe", "shader/ui_gui_glass.fsh");
            glassPipeline = (RenderPipeline) gpu.buildPipeline("ui_gui_glass", glassVsh, glassFsh,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                RenderPipelines.GUI, shapeFormat);
            glassShaderSource = gpu.shaderSource(glassVsh, GuiElementShaders.GLASS_VERTEX,
                glassFsh, GuiElementShaders.GLASS_FRAGMENT);
        } catch (Throwable t) {
            // Jamais muet, mais jamais fatal non plus — voir la javadoc.
            LauncherLog.err("[VanillaGuiSink1211] construction des pipelines secondaires : " + t);
        }
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
            // Les trois secondaires peuvent manquer sans que le HUD tombe
            // (voir buildExtras) : on ne précompile que ce qui existe.
            if (iconPipeline != null) gpu.precompile(device, iconPipeline, iconShaderSource);
            if (vignettePipeline != null) gpu.precompile(device, vignettePipeline, vignetteShaderSource);
            if (glassPipeline != null) gpu.precompile(device, glassPipeline, glassShaderSource);
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

    /**
     * L'atlas d'icônes 2048² PARTAGÉ avec la file Blaze3D : une icône déjà
     * packée pour celle-ci est utilisable ici sans seconde copie GPU, et toutes
     * portent le même {@code TextureSetup}, donc se regroupent en un maillage
     * unique quand elles se suivent.
     */
    private TextureSetup atlasSetup;

    @Override
    public boolean icon(Object hookContext, String cacheKey, BufferedImage img,
                        float x0, float y0, float x1, float y1, float alpha) {
        if (img == null) return false;
        GuiRenderState state = stateOf(hookContext);
        if (state == null || !buildPipelines()) return false;
        if (iconPipeline == null) {
            reportOnce("icône : pipeline indisponible");
            return false;
        }
        try {
            Object[] entry = Blaze3DRect.guiAtlasEntry(cacheKey, img);
            if (entry == null) {
                // Atlas plein ou Blaze3D indisponible — déjà journalisé là-bas.
                return false;
            }
            if (atlasSetup == null) {
                atlasSetup = TextureSetup.of((GpuTextureView) entry[1], (GpuSampler) entry[2]);
            }
            // Blanc modulé par l'opacité demandée : le fragment multiplie la
            // couleur de sommet par le texel, donc blanc = image telle quelle.
            int argb = (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0x00FFFFFF;
            state.addSimpleElement(new IconElement1211(x0, y0, x1, y1,
                (float[]) entry[0], argb, iconPipeline, atlasSetup));
            return true;
        } catch (Throwable t) {
            reportOnce("icon : " + t);
            return false;
        }
    }

    @Override
    public boolean vignette(Object hookContext, float x0, float y0, float x1, float y1,
                            float vSize, UiColor color) {
        GuiRenderState state = stateOf(hookContext);
        if (state == null || !buildPipelines()) return false;
        if (vignettePipeline == null) {
            reportOnce("vignette : pipeline indisponible");
            return false;
        }
        try {
            state.addSimpleElement(new VignetteElement1211(x0, y0, x1, y1, vSize,
                argb(color), vignettePipeline));
            return true;
        } catch (Throwable t) {
            reportOnce("vignette : " + t);
            return false;
        }
    }

    /**
     * {@code true} depuis le portage du verre (2026-09-12).
     *
     * <p>Ce drapeau commande la chaîne de flou : le répondre à tort ferait
     * payer plusieurs passes plein écran par frame pour un résultat jamais
     * dessiné — c'est pour ça que {@code VanillaGuiTarget.beginGlassFrame} le
     * consulte AVANT de lancer la chaîne.
     */
    @Override
    public boolean supportsGlassPanel() {
        return true;
    }

    @Override
    public boolean glassPanel(Object hookContext, float x0, float y0, float x1, float y1,
                              float rTopLeft, float rTopRight, float rBottomLeft, float rBottomRight,
                              UiColor tint, UiColor background) {
        GuiRenderState state = stateOf(hookContext);
        if (state == null || !buildPipelines()) return false;
        if (glassPipeline == null) {
            reportOnce("verre : pipeline indisponible");
            return false;
        }
        try {
            TextureSetup blurred = blurSetup();
            if (blurred == null) {
                // Aucune chaîne de flou pour cette frame : l'appelant retombe
                // sur son fond plein plutôt que d'échantillonner n'importe quoi.
                reportOnce("verre : chaîne de flou indisponible pour cette frame");
                return false;
            }
            // rgb = TEINTE, a = opacité du FOND : le fragment mélange le flou
            // avec la teinte puis applique l'alpha. Prendre l'alpha de la
            // teinte à la place (l'erreur naturelle ici) donnerait un panneau
            // d'une opacité sans rapport — même composition qu'en 26.1.2.
            int argb = (Math.round(background.a * 255f) << 24)
                     | (Math.round(tint.r * 255f) << 16)
                     | (Math.round(tint.g * 255f) << 8)
                     |  Math.round(tint.b * 255f);
            state.addSimpleElement(new GlassPanelElement1211(x0, y0, x1, y1,
                rTopLeft, rTopRight, rBottomLeft, rBottomRight,
                argb, glassPipeline, blurred));
            return true;
        } catch (Throwable t) {
            reportOnce("glassPanel : " + t);
            return false;
        }
    }

    /**
     * {@code TextureSetup} sur le résultat de la chaîne de flou — JAMAIS mis en
     * cache, contrairement à l'atlas : la chaîne est recréée à chaque
     * changement de résolution, et sa vue avec.
     */
    private TextureSetup blurSetup() {
        Object view = Blaze3DBlur.blurredView();
        Object sampler = Blaze3DBlur.blurSampler();
        if (view == null || sampler == null) return null;
        return TextureSetup.of((GpuTextureView) view, (GpuSampler) sampler);
    }

    /**
     * Vide la file d'icônes d'item DANS l'état de GUI de cette passe —
     * désormais identique à la 26.1.2 (2026-09-12).
     *
     * <p>C'était un no-op jusqu'ici : les icônes attendaient
     * {@code GuiFlushMixin1211}, accroché plus tard sur
     * {@code GuiRenderer.render}. Tout y est pourtant en place (hook tissé,
     * entrée de refmap, constructeur public de {@code DrawContext},
     * {@code drawItem}/{@code drawItemBar} présentes, champ {@code state}
     * lisible, injection AVANT la préparation de l'atlas d'items) — et
     * pourtant les icônes d'armure ne s'affichaient pas.
     *
     * <p>Plutôt que de continuer à chercher une différence invisible, on
     * reprend le chemin qui MARCHE en 26.1.2 : vider la file ici, dans la même
     * passe et avec le même état que le reste du HUD. Les deux chemins ne se
     * marchent pas dessus — la file se vide, donc le second flush ne trouve
     * plus rien.
     */
    @Override
    public void flushItemIcons(Object hookContext) {
        GuiRenderState state = stateOf(hookContext);
        if (state == null) return;
        try {
            UiRenderer.flushPendingModernItemIconsFromState(state);
        } catch (Throwable t) {
            reportOnce("vidage des icônes d'item : " + t);
        }
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
