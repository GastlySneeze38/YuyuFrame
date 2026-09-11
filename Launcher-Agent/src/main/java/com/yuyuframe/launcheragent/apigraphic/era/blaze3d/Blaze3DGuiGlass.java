package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.RenderPipelinesAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.VertexFormatElementAccessor261;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Panneau de VERRE DÉPOLI destiné à l'état de GUI de vanilla — troisième et
 * dernière primitive du HUD à être portée (2026-08-31).
 *
 * <h2>Pourquoi pas le flou de vanilla</h2>
 *
 * Vanilla expose {@code blurBeforeThisStratum()}, mais c'est un post-effet
 * PLEIN ÉCRAN et DESTRUCTIF : {@code GuiRenderer.draw} appelle
 * {@code GameRenderer.processBlurEffect()}, qui exécute
 * {@code post_effect/blur.json} — <b>six</b> passes de flou-boîte séparable —
 * en réécrivant le framebuffer principal. Tout ce qui est dessiné avant la
 * strate est flouté PARTOUT. C'est le flou du menu pause, pas du verre
 * localisé : l'utiliser pour le HUD flouterait toute la vue de jeu.
 *
 * <p>Notre chaîne dual-Kawase fait moins de passes (trois pour le HUD),
 * n'écrase pas le framebuffer, et produit une texture qu'on masque ensuite à
 * la forme du panneau. Elle reste donc le bon outil ici.
 *
 * <h2>Le créneau manquant, et comment on s'en passe</h2>
 *
 * Le format porte déjà position locale (UV0), demi-taille (UV1) et rayons
 * (UV2) : plus rien de libre pour la taille de l'écran, nécessaire pour
 * convertir un fragment en coordonnée de texture. {@code textureSize(Sampler0, 0)}
 * la donne directement en GLSL — aucun attribut ni uniforme supplémentaire.
 *
 * <p>La teinte voyage dans l'attribut {@code Color} : {@code rgb} = couleur de
 * teinte, {@code a} = opacité finale du panneau. La FORCE de teinte n'ayant
 * plus de créneau, elle est figée à la valeur du HUD — voir
 * {@link #TINT_STRENGTH}.
 */
public final class Blaze3DGuiGlass {
    private Blaze3DGuiGlass() {}

    /**
     * Force de teinte, figée faute de créneau libre par sommet. Reprend
     * {@code UiTheme.GLASS_STRENGTH_FIELD}, la seule valeur que le HUD
     * utilisait ({@code HudPanelRenderer} n'en passe pas d'autre).
     */
    public static final float TINT_STRENGTH = 0.35f;

    private static final String VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV1;\n" +
        "in ivec2 UV2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 localPos;\n" +
        "flat out vec2 halfSize;\n" +
        "flat out vec4 radii;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        "    radii = vec4(float((UV2.x >> 8) & 255), float(UV2.x & 255),\n" +
        "                 float((UV2.y >> 8) & 255), float(UV2.y & 255));\n" +
        "}\n";

    private static final String FRAGMENT_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "uniform sampler2D Sampler0;\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localPos;\n" +
        "flat in vec2 halfSize;\n" +
        "flat in vec4 radii;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        // Coordonnée d'échantillonnage déduite du fragment : gl_FragCoord est
        // en pixels de fenêtre, textureSize donne la taille de la texture
        // floutée. Pas d'attribut ni d'uniforme à ajouter pour ça.
        "    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));\n" +
        "    vec3 blurred = texture(Sampler0, uv).rgb;\n" +
        "    float radius = (localPos.y < 0.0)\n" +
        "        ? ((localPos.x < 0.0) ? radii.x : radii.y)\n" +
        "        : ((localPos.x < 0.0) ? radii.z : radii.w);\n" +
        "    vec2 q = abs(localPos) - halfSize + radius;\n" +
        "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "    float mask = 1.0 - smoothstep(-0.5, 0.5, d);\n" +
        "    if (mask <= 0.001) discard;\n" +
        "    vec3 rgb = mix(blurred, vertexColor.rgb, " + TINT_STRENGTH + ");\n" +
        "    fragColor = vec4(rgb, vertexColor.a * mask) * ColorModulator;\n" +
        "}\n";

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) { buildFailed = true; return null; }
                VertexFormat format = VertexFormat.builder()
                    .add("Position", VertexFormatElementAccessor261.la$position())
                    .add("Color", VertexFormatElementAccessor261.la$color())
                    .add("UV0", VertexFormatElementAccessor261.la$uv0())
                    .add("UV1", VertexFormatElementAccessor261.la$uv1())
                    .add("UV2", VertexFormatElementAccessor261.la$uv2())
                    .build();
                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_glass.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_glass.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_glass", vsh, fsh,
                    new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor261.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiGlass] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiGlass] précompilation: " + t);
            return false;
        }
    }

    /**
     * {@code TextureSetup} sur le résultat de la chaîne de flou, ou
     * {@code null} si aucune chaîne n'a encore été calculée pour cette frame.
     *
     * <p>PAS mis en cache, contrairement à l'atlas de police : la chaîne est
     * recréée à chaque changement de résolution, et sa vue avec.
     */
    public static TextureSetup textureSetup() {
        try {
            Object view = Blaze3DBlur.blurredView();
            Object sampler = Blaze3DBlur.blurSampler();
            if (view == null || sampler == null) return null;
            return TextureSetup.singleTexture((GpuTextureView) view, (GpuSampler) sampler);
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiGlass] textureSetup: " + t);
            return null;
        }
    }
}
