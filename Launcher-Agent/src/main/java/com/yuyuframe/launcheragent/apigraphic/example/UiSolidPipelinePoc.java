package com.yuyuframe.launcheragent.apigraphic.example;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpu;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpus;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.nio.ByteBuffer;

/**
 * Preuve de mécanisme (roadmap Phase 5, "pipeline shader maison" — voir
 * {@link ShaderPipelineFactory} pour le contexte complet) : dessine UN quad
 * de couleur plate via un {@code RenderPipeline} ET un GLSL ENTIÈREMENT
 * maison, sans jamais toucher à {@code RenderPipelines.GUI_TEXT} pour le
 * rendu réel (seulement pour COPIER son format de sommets/état GPU au
 * moment de construire notre pipeline).
 *
 * Isolé/jetable — n'est branché sur AUCUN draw call de l'UI existante.
 * Déclenché uniquement par {@code /yf shaderpoc} (voir {@code YfCommands}),
 * pour vérifier visuellement en jeu que la précompilation d'un pipeline avec
 * du GLSL 100% maison fonctionne sur ce bracket.
 *
 * GLSL volontairement SANS uniform : positions codées en dur en NDC dans le
 * vertex shader (indexées via {@code gl_VertexID}), couleur plate en sortie
 * du fragment shader — isole le test au seul mécanisme "précompiler+dessiner
 * avec notre propre GLSL", sans entraîner la machinerie DynamicTransforms/
 * Projection existante. Un vrai vertex buffer (4 sommets, même disposition
 * 28 octets que {@code RenderPipelines.GUI_TEXT} — position/couleur/uv/light,
 * voir {@code Blaze3DCore.putVertexPCTL}) reste bindé malgré tout : notre
 * shader ignore son contenu, mais le lier respecte le contrat de binding du
 * pipeline (même {@code VertexFormat} que GUI_TEXT, copié tel quel).
 *
 * <p>Sans réflexion (étape 2c) : tout passe par {@link Blaze3DGpu}.
 */
public final class UiSolidPipelinePoc {
    private UiSolidPipelinePoc() {}

    /** Toggle via {@code /yf shaderpoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean testEnabled = false;

    private static final String VERTEX_SRC =
        "#version 330\n" +
        "const vec2 POSITIONS[4] = vec2[](\n" +
        "    vec2(-0.3, -0.3), vec2(0.3, -0.3), vec2(-0.3, 0.3), vec2(0.3, 0.3)\n" +
        ");\n" +
        "void main() {\n" +
        "    gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0);\n" +
        "}\n";

    private static final String FRAGMENT_SRC =
        "#version 330\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    fragColor = vec4(1.0, 0.2, 0.8, 1.0);\n" +
        "}\n";

    private static String currentStage = "";

    private static Object pipeline;
    private static Object shaderSource;
    private static Object vertexBuffer;

    /** 28 octets/sommet — même disposition que {@code RenderPipelines.GUI_TEXT} (position/couleur/uv/light) ; notre shader ignore tout sauf {@code gl_VertexID}, seul le contrat de binding importe ici. */
    private static void putVertex(ByteBuffer buf, float x, float y) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        buf.putFloat(0f).putFloat(0f);
        buf.putShort((short) 0).putShort((short) 0);
    }

    /** Dessine un quad de couleur plate via notre pipeline maison — {@code true} si le draw a réussi, {@code false}/log sinon (voir {@code currentStage} dans les logs pour l'étape exacte). */
    public static boolean drawTestQuad() {
        if (!ShaderPipelineFactory.isAvailable()) return false;
        Blaze3DGpu gpu = Blaze3DGpus.active();
        if (gpu == null) {
            LauncherLog.err("[LauncherAgent] UiSolidPipelinePoc: aucune implémentation Blaze3DGpu pour cette version");
            return false;
        }
        try {
            currentStage = "device";
            Object device = gpu.device();

            if (pipeline == null) {
                currentStage = "identifiers";
                Object vertexId = gpu.identifier("yuyuframe", "shader/poc_solid.vsh");
                Object fragmentId = gpu.identifier("yuyuframe", "shader/poc_solid.fsh");
                currentStage = "buildPipeline";
                pipeline = gpu.buildPipeline("poc_solid", vertexId, fragmentId,
                    new String[0], new String[0], null, null);
                currentStage = "shaderSource";
                shaderSource = gpu.shaderSource(vertexId, VERTEX_SRC, fragmentId, FRAGMENT_SRC);
                LauncherLog.ui(1, "[LauncherAgent] UiSolidPipelinePoc: pipeline maison construit avec succès (voir /yf shaderpoc)");
            }
            // Vérifié contre UniversalCraft (URenderPipeline.kt, précédent
            // direct pour ce même mécanisme sur cette ère Blaze3D) : ils
            // rappellent precompilePipeline(pipeline, source) à CHAQUE draw,
            // pas une seule fois à la construction — leur commentaire :
            // "need to do this each draw (it'll no-op if it's already
            // cached) because resource reloads will clear it again".
            currentStage = "precompile";
            gpu.precompile(device, pipeline, shaderSource);

            currentStage = "mainColorView";
            Object colorView = gpu.mainColorView();
            if (colorView == null) return false;

            currentStage = "encoder";
            Object encoder = gpu.encoder(device);

            if (vertexBuffer == null) {
                currentStage = "createVertexBuffer";
                vertexBuffer = gpu.createBuffer(device, "yuyuframe_shaderpoc_vbo",
                    gpu.usageBufferVertex() | gpu.usageBufferCopyDst(), 4L * 28L);
            }
            ByteBuffer verts = ByteBuffer.allocateDirect(4 * 28).order(java.nio.ByteOrder.nativeOrder());
            putVertex(verts, -0.3f, -0.3f);
            putVertex(verts, 0.3f, -0.3f);
            putVertex(verts, -0.3f, 0.3f);
            putVertex(verts, 0.3f, 0.3f);
            verts.flip();
            currentStage = "writeToBuffer";
            gpu.write(encoder, gpu.slice(vertexBuffer, 0L, verts.remaining()), verts);

            currentStage = "openPass";
            Object pass = gpu.openPass(encoder, "yuyuframe_shaderpoc", colorView);
            try {
                currentStage = "setPipeline";
                gpu.setPipeline(pass, pipeline);
                currentStage = "disableScissor";
                gpu.disableScissor(pass);
                currentStage = "setVertexBuffer";
                gpu.setVertexBuffer(pass, 0, vertexBuffer);
                // GUI_TEXT est en mode QUADS : un draw NON indexé ne triangule
                // pas tout seul — drawQuads passe par le tampon d'indices
                // séquentiel partagé du jeu (bug historique du texte invisible).
                currentStage = "drawQuads";
                gpu.drawQuads(pass, 1);
            } finally {
                currentStage = "closePass";
                gpu.closePass(pass);
            }
            return true;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[LauncherAgent] UiSolidPipelinePoc.drawTestQuad a échoué à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            return false;
        }
    }
}
