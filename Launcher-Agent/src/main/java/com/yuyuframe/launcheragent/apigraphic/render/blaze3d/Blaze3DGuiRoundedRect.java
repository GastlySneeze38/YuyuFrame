package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
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
        // flat : constants par primitive, jamais interpolés — seule localPos
        // doit varier d'un sommet à l'autre.
        "flat out vec2 halfSize;\n" +
        "flat out vec4 radii;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        // DEUX rayons par entier court : chacun tient sur 8 bits (0-255 pixels
        // GUI, très au-delà du maximum réglable de 16). C'est ce qui permet
        // QUATRE rayons sans ajouter d'attribut au format de sommet.
        "    radii = vec4(float((UV2.x >> 8) & 255), float(UV2.x & 255),\n" +
        "                 float((UV2.y >> 8) & 255), float(UV2.y & 255));\n" +
        "}\n";

    /** SDF de boîte arrondie (formule d'Inigo Quilez), antialiasée sur un pixel. */
    private static final String FRAGMENT_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localPos;\n" +
        "flat in vec2 halfSize;\n" +
        // (haut-gauche, haut-droit, bas-gauche, bas-droit) — repère Y VERS LE
        // BAS, celui de la GUI vanilla : localPos.y négatif = haut de l'écran.
        "flat in vec4 radii;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        // Le rayon est choisi PAR FRAGMENT selon le quadrant : c'est ce qui
        // permet à un panneau collé à un bord d'écran de garder ses coins
        // carrés de ce côté-là (voir HudPanelRenderer.edgeAwareRadii).
        "    float radius = (localPos.y < 0.0)\n" +
        "        ? ((localPos.x < 0.0) ? radii.x : radii.y)\n" +
        "        : ((localPos.x < 0.0) ? radii.z : radii.w);\n" +
        "    vec2 q = abs(localPos) - halfSize + radius;\n" +
        "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "    float alpha = 1.0 - smoothstep(-0.5, 0.5, d);\n" +
        "    if (alpha <= 0.001) discard;\n" +
        "    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;\n" +
        "}\n";

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
            // isAvailable() ne déclenche PAS la résolution des méthodes (voir
            // Blaze3DVanillaProbe) — resolve() est idempotent.
            if (!Blaze3DCore.resolve() || Blaze3DCore.mGetDevice == null) {
                LauncherLog.err("[Blaze3DGuiRoundedRect] Blaze3DCore non résolu");
                return false;
            }
            ShaderPipelineFactory.precompile(Blaze3DCore.mGetDevice.invoke(null), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiRoundedRect] précompilation: " + t);
            return false;
        }
    }
}
