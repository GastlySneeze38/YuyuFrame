package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

/**
 * Pipeline minimal destiné à être soumis DANS l'état de GUI de vanilla —
 * étape 1 de la refonte du rendu (2026-08-30), voir
 * {@code apigraphic/render/vanillagui/VanillaGuiLayer}.
 *
 * <p>Vit dans ce paquet, et pas à côté de {@code VanillaGuiLayer}, uniquement
 * pour atteindre les membres package-private de {@link Blaze3DCore}
 * ({@code mGetDevice}, la fabrique de pipelines) — comme {@code Blaze3DRect}
 * et {@code Blaze3DText}.
 *
 * <h2>Deux contraintes qui dictent tout ce fichier</h2>
 *
 * <ol>
 *   <li><b>Uniquement des uniformes que vanilla lie lui-même.</b> Ce n'est
 *       plus nous qui exécutons le dessin : {@code GuiRenderer} soumet
 *       l'élément plus tard et ne connaît que ses propres blocs. Un
 *       {@code BatchParams} comme celui du chemin batch ne serait jamais lié —
 *       d'où la déclaration réduite à {@code DynamicTransforms} et
 *       {@code Projection}.</li>
 *   <li><b>Uniquement les attributs que vanilla écrit.</b> Lecture du bytecode
 *       de {@code ColoredRectangleRenderState.buildVertices} : il n'appelle que
 *       {@code addVertexWith2DPose} puis {@code setColor} — donc
 *       {@code Position} et {@code Color}, rien d'autre. Le shader ci-dessous
 *       s'y tient strictement.</li>
 * </ol>
 *
 * <p>C'est aussi la raison pour laquelle cette étape s'arrête au quad de
 * couleur pleine : un SDF de coin arrondi a besoin, en plus de la position
 * locale, de la demi-taille et du rayon. Ça ne tient pas dans les attributs
 * disponibles, et il faudra un {@code VertexFormat} personnalisé — étape 1b,
 * séparée exprès pour ne pas tester deux inconnues à la fois.
 */
public final class Blaze3DVanillaProbe {
    private Blaze3DVanillaProbe() {}

    /**
     * Quad plein, sans texture. {@code Position}/{@code Color} sont les deux
     * seuls attributs alimentés par vanilla ; {@code ColorModulator} vient de
     * {@code DynamicTransforms}, que vanilla lie systématiquement.
     */
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
        "out vec4 vertexColor;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "}\n";

    /**
     * Teinte volontairement RECONNAISSABLE (magenta translucide) : le but de
     * l'étape 1 est de voir OÙ le quad atterrit dans l'empilement, pas de
     * faire joli. Une couleur qu'on ne confondra avec aucun élément vanilla.
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
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    fragColor = vec4(1.0, 0.0, 0.85, 0.65) * ColorModulator;\n" +
        "}\n";

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    /**
     * Le pipeline, construit à la première demande — ou {@code null} s'il est
     * indisponible (hors bracket era E, ou échec de construction déjà
     * journalisé).
     */
    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) return null;
                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_vanilla_probe.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_vanilla_probe.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_vanilla_probe", vsh, fsh,
                    new String[0], new String[]{ "DynamicTransforms", "Projection" });
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DVanillaProbe] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /**
     * Compile le pipeline si ce n'est pas déjà fait.
     *
     * <p>À appeler AVANT de soumettre l'élément à vanilla : c'est vanilla qui
     * dessinera, plus tard dans la frame, et il ne précompile pas nos
     * pipelines. Réappelé à chaque émission comme partout ailleurs dans
     * {@code blaze3d} — no-op si déjà en cache, mais nécessaire après un
     * rechargement de ressources (F3+T) qui vide le cache du device.
     *
     * @return {@code true} si le pipeline est prêt à être soumis.
     */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            Object device = Blaze3DCore.mGetDevice.invoke(null);
            ShaderPipelineFactory.precompile(device, p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DVanillaProbe] précompilation: " + t);
            return false;
        }
    }
}
