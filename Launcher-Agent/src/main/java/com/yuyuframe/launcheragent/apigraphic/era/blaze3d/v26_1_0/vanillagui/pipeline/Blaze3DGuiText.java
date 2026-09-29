package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.pipeline;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DText;
import com.yuyuframe.launcheragent.apimixin.v26_1_0.render.RenderPipelinesAccessor2610;
import com.yuyuframe.launcheragent.apimixin.v26_1_0.render.VertexFormatElementAccessor2610;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Pendant de {@link Blaze3DGuiRoundedRect} pour le TEXTE — pipeline et format
 * de sommet du texte SDF destiné à l'état de GUI de vanilla.
 *
 * <p>Réutilise EXACTEMENT les mêmes sources GLSL que {@link Blaze3DText}
 * ({@code TEXT_VERTEX_SRC}/{@code TEXT_FRAGMENT_SRC}, ouvertes au paquet pour
 * l'occasion) : même rendu, même bias SDF, même gestion de la couleur par
 * sommet. Seuls changent le format de sommet (déclaré ici plutôt qu'emprunté)
 * et le fait que ce n'est plus nous qui exécutons la passe.
 *
 * <p>Format volontairement RÉDUIT à ce que le shader consomme :
 *
 * <pre>
 * Position  vec3
 * Color     vec4   couleur par sommet — permet plusieurs couleurs par lot
 * UV0       vec2   coordonnées dans l'atlas de police
 * </pre>
 *
 * <p>Pas d'{@code UV2} ici, contrairement au format de {@code GUI_TEXT} :
 * chaque attribut déclaré DOIT être écrit à chaque sommet, en déclarer un de
 * plus obligerait à le remplir pour rien.
 *
 * <p>L'atlas de police est celui du moteur ({@code Blaze3DText.ensureTexture}) —
 * vanilla le lie sur {@code Sampler0}, exactement ce qu'attend le fragment.
 */
public final class Blaze3DGuiText {
    private Blaze3DGuiText() {}

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    /** Le pipeline texte, construit à la première demande — {@code null} si indisponible. */
    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) { buildFailed = true; return null; }

                VertexFormat format = VertexFormat.builder()
                    .add("Position", VertexFormatElementAccessor2610.la$position())
                    .add("Color", VertexFormatElementAccessor2610.la$color())
                    .add("UV0", VertexFormatElementAccessor2610.la$uv0())
                    .build();

                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_text.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_text.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_text", vsh, fsh,
                    new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor2610.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh,
                    Blaze3DText.TEXT_VERTEX_SRC, fsh, Blaze3DText.TEXT_FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiText] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /** Compile le pipeline — voir {@code Blaze3DGuiRoundedRect.ensureCompiled} pour le pourquoi. */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiText] précompilation: " + t);
            return false;
        }
    }

    /**
     * {@code TextureSetup} pointant sur l'atlas de {@code font}, ou
     * {@code null} si l'atlas n'a pas pu être préparé.
     *
     * <p>{@code ensureTexture} renvoie {@code [GpuTexture, GpuTextureView,
     * GpuSampler]} — seuls les deux derniers nous intéressent.
     */
    private static final java.util.Map<UiFont, TextureSetup> SETUPS = new java.util.HashMap<>();

    public static TextureSetup textureSetup(UiFont font) {
        // MIS EN CACHE par police (2026-08-30) : appelé une fois par chaîne de
        // texte et par frame, il allouait à chaque fois un TextureSetup neuf en
        // plus de traverser ensureTexture. L'atlas d'une police ne change
        // jamais après sa création, la valeur est donc valable pour la session.
        TextureSetup cached = SETUPS.get(font);
        if (cached != null) return cached;
        try {
            Object[] tex = Blaze3DText.ensureTexture(font);
            if (tex == null || tex.length < 3) return null;
            TextureSetup setup = TextureSetup.singleTexture((GpuTextureView) tex[1], (GpuSampler) tex[2]);
            if (setup != null) SETUPS.put(font, setup);
            return setup;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiText] textureSetup: " + t);
            return null;
        }
    }
}
