package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1.vanillagui.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.GuiElementShaders;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.RenderPipelinesAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.VertexFormatElementAccessor261;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Format de sommet et pipeline d'un RECT ARRONDI destiné à l'état de GUI de
 * vanilla — étape 1c de la refonte du rendu (2026-08-30), voir
 * {@code docs/LauncherAgent/rendering-pipeline.md}.
 *
 * <h2>Pourquoi un format maison</h2>
 *
 * Le format non texturé de vanilla ({@code RenderPipelines.GUI}) ne porte que
 * {@code Position + Color} : aucun attribut libre. Or un SDF de coin arrondi a
 * besoin de CINQ scalaires par sommet — position locale (2), demi-taille (2),
 * rayon (1). D'où un {@code VertexFormat} déclaré ici :
 *
 * <pre>
 * Position  vec3    coin du quad, en pixels GUI
 * Color     vec4    couleur de remplissage
 * UV0       vec2    position locale, relative au centre du rect (flottants)
 * UV1       ivec2   (demi-largeur, demi-hauteur)      entiers courts
 * UV2       ivec2   4 rayons, empaquetés 2 par entier entiers courts
 *                   x = (hautGauche &lt;&lt; 8) | hautDroit
 *                   y = (basGauche  &lt;&lt; 8) | basDroit
 * </pre>
 *
 * <p>Ces trois derniers ne sont pas des UV au sens texture : ce sont les seuls
 * créneaux que {@code VertexConsumer} sait alimenter ({@code setUv},
 * {@code setUv1}, {@code setUv2}) et qui offrent la précision voulue. Les
 * entiers courts suffisent largement : demi-tailles et rayons sont des pixels
 * GUI, jamais au-delà de quelques centaines.
 *
 * <p>Les constantes {@code VertexFormatElement} passent par un ACCESSOR
 * ({@link VertexFormatElementAccessor261}), pas par un accès direct — norme du
 * projet, voir sa javadoc.
 *
 * <h2>Contraintes héritées de l'étape précédente</h2>
 *
 * Le pipeline ne déclare QUE {@code DynamicTransforms} et {@code Projection} :
 * c'est {@code GuiRenderer} qui soumet le dessin, et il ne lie pas nos blocs
 * d'uniformes maison. Toute donnée variable doit donc passer par les sommets —
 * c'est précisément ce que ce format rend possible.
 */
public final class Blaze3DGuiRoundedRect {
    private Blaze3DGuiRoundedRect() {}

    // GLSL PARTAGÉ avec la 1.21.11 (voir GuiElementShaders) : le rendu diffère
    // d'une version à l'autre, le shader non. Constantes de compilation, donc
    // recopiées ici par javac — aucune classe chargée en plus au runtime.
    private static final String VERTEX_SRC = GuiElementShaders.ROUNDED_RECT_VERTEX;

    private static final String FRAGMENT_SRC = GuiElementShaders.ROUNDED_RECT_FRAGMENT;

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    /** Le pipeline, construit à la première demande — {@code null} si indisponible. */
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

                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_rounded_rect.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_rounded_rect.fsh");
                // Référence = GUI (mode de primitive, états couleur/profondeur/cull),
                // mais avec NOTRE format de sommet.
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_rounded_rect", vsh, fsh,
                    new String[0], new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor261.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiRoundedRect] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /**
     * Compile le pipeline si besoin. À appeler AVANT de soumettre l'élément :
     * c'est vanilla qui dessinera plus tard, et il ne précompile pas nos
     * pipelines.
     */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiRoundedRect] précompilation: " + t);
            return false;
        }
    }
}
