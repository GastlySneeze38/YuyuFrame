package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.RenderPipelinesAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.render.VertexFormatElementAccessor261;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

/**
 * Format de sommet et pipeline du DÉGRADÉ DE BORD (vignette plein écran)
 * destiné à l'état de GUI de vanilla — portage de {@code LowHealthTintModule}
 * sur le nouveau chemin de rendu (2026-08-31), voir
 * {@code docs/LauncherAgent/rendering-pipeline.md}.
 *
 * <h2>Pourquoi ce pipeline existe</h2>
 *
 * L'ancienne vignette dessinait en <b>OpenGL brut</b> ({@code glUseProgram},
 * {@code glDisable}, {@code glBlendFunc}, VAO maison — voir
 * {@code UiPrimitiveRenderer.drawEdgeVignetteModern}), APRÈS la présentation
 * de la frame. Sur 26.1.2, Blaze3D suit lui-même l'état GPU : y toucher
 * derrière son dos corrompt ce qu'il croit avoir posé, d'où les artefacts
 * signalés dès l'activation du module. Émettre un
 * {@code GuiElementRenderState} comme n'importe quel autre élément de GUI
 * supprime le problème à la racine — plus une seule commande GL de notre
 * part.
 *
 * <h2>Format de sommet</h2>
 *
 * Même principe que {@link Blaze3DGuiRoundedRect} (lire sa javadoc pour le
 * pourquoi général : {@code GuiRenderer} ne lie aucun bloc d'uniformes à nous,
 * donc TOUTE donnée variable passe par les sommets) :
 *
 * <pre>
 * Position  vec3    coin du quad, en pixels GUI
 * Color     vec4    couleur AU BORD, alpha compris
 * UV0       vec2    position locale, relative au centre de l'écran
 * UV1       ivec2   (demi-largeur, demi-hauteur) de l'écran, pixels GUI
 * UV2       ivec2   x = largeur du dégradé en pixels GUI ; y inutilisé
 * </pre>
 *
 * <p>Le dégradé se calcule à partir de la distance au bord le plus PROCHE,
 * qui est un {@code min()} de quatre fonctions linéaires : non planaire, donc
 * impossible à interpoler entre quatre sommets. C'est bien un calcul par
 * fragment, pas un dégradé de sommets.
 */
public final class Blaze3DGuiVignette {
    private Blaze3DGuiVignette() {}

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
        // flat : constants par primitive — seule localPos doit varier.
        "flat out vec2 halfSize;\n" +
        "flat out float vSize;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        "    vSize = float(UV2.x);\n" +
        "}\n";

    /**
     * Reprend TRAIT POUR TRAIT la courbe de l'ancien shader GL brut — même
     * smootherstep, même dithering : le portage change le chemin de rendu, pas
     * l'apparence. Les deux commentaires d'origine valent toujours et sont
     * conservés ici, ce sont des conclusions durement acquises.
     */
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
        "flat in float vSize;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        // Distance au bord le plus proche : halfSize - |localPos| donne la
        // distance à chaque paire de bords, le min() des deux axes donne le
        // bord le plus proche des quatre. Le chevauchement des dégradés
        // opposés quand la largeur dépasse 50% de l'écran est géré nativement
        // par ce min(), sans double comptage.
        "    vec2 d = halfSize - abs(localPos);\n" +
        "    float distEdge = min(d.x, d.y);\n" +
        // smootherstep (Ken Perlin, 6t^5-15t^4+10t^3) : dérivée première ET
        // seconde nulles aux deux bornes, la référence pour ce type de
        // dégradé. Une tentative d'"ease-out" (1-t)^3 avait été essayée à
        // l'époque du chemin GL brut ; elle ne servait à rien, le bord net
        // venait d'un GL_ALPHA_TEST resté actif — problème qui ne peut plus
        // se poser ici, le pipeline déclarant lui-même son état.
        "    float t = clamp(distEdge / max(vSize, 1.0), 0.0, 1.0);\n" +
        "    float eased = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);\n" +
        "    float alpha = 1.0 - eased;\n" +
        // Le framebuffer ne code que 256 niveaux par canal — même une courbe
        // parfaitement lisse en maths QUANTIFIE en paliers visibles sur une
        // zone large. Un bruit d'environ 1 LSB les casse (technique standard
        // contre le banding des dégradés plein écran).
        "    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\n" +
        "    alpha = clamp(alpha + dither / 128.0, 0.0, 1.0);\n" +
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

                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_vignette.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_vignette.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_vignette", vsh, fsh,
                    new String[0], new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor261.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiVignette] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /**
     * Compile le pipeline si besoin. À appeler AVANT de soumettre l'élément :
     * c'est vanilla qui dessinera plus tard, et il ne précompile pas nos
     * pipelines.
     *
     * <p>Volontairement PAS appelé depuis {@code VanillaGuiTarget.begin()},
     * contrairement aux pipelines du rect et du texte : la vignette ne sert
     * qu'à un module, et à vie basse seulement. La faire compiler à chaque
     * passe de HUD ferait payer sa construction à tout le monde, et un échec
     * de sa part ferait tomber le HUD entier.
     */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            // isAvailable() ne déclenche PAS la résolution des méthodes (voir
            // Blaze3DVanillaProbe) — resolve() est idempotent.
            if (!Blaze3DCore.resolve() || Blaze3DCore.mGetDevice == null) {
                LauncherLog.err("[Blaze3DGuiVignette] Blaze3DCore non résolu");
                return false;
            }
            ShaderPipelineFactory.precompile(Blaze3DCore.mGetDevice.invoke(null), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiVignette] précompilation: " + t);
            return false;
        }
    }
}
