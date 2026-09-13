package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import static com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore.*;

import java.nio.ByteBuffer;

/**
 * Modes de fusion multiply/screen/overlay (roadmap Phase 5.4, différenciateur
 * visuel vs Lunar/Badlion) — un rect arrondi dont la couleur RÉSULTANTE se
 * calcule à partir du fond ACTUELLEMENT affiché derrière lui, PAS un simple
 * blend GPU {@code glBlendFunc} classique (qui ne peut exprimer que des
 * combinaisons linéaires src/dst, pas la formule non-linéaire d'un vrai
 * "overlay" façon Photoshop/CSS {@code mix-blend-mode}).
 *
 * Même technique que {@link Blaze3DBlur} (déjà validée cette session) :
 * échantillonne {@code mc.getFramebuffer().getColorAttachmentView()} comme
 * {@code Sampler0} — DÉJÀ {@code USAGE_TEXTURE_BINDING} (voir
 * {@link Blaze3DBlur} pour la vérification javap) — calcule la formule de
 * fusion PAR PIXEL dans le shader, écrit directement le résultat (le GPU
 * fait ensuite un blend alpha STANDARD par-dessus, pour respecter l'opacité
 * propre de la forme — voir {@code u_TopColor.a} dans le fragment shader).
 */
public final class Blaze3DBlend {
    private Blaze3DBlend() {}

    public static final int MODE_MULTIPLY = 0;
    public static final int MODE_SCREEN = 1;
    public static final int MODE_OVERLAY = 2;

    /** Vertex identique à {@link Blaze3DCore#RECT_VERTEX_SRC} (Position+Color -> fragPos+vertexColor) — même source réutilisée, nouvel identifiant dédié. */
    private static final String BLEND_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform RectParams {\n" +
        "    vec4 u_Rect;\n" +
        "    vec4 u_CornerRadii;\n" +
        "};\n" +
        "layout(std140) uniform BlendParams {\n" +
        "    vec4 u_TopColor;\n" +
        "    vec4 u_ModeAndScreen;\n" + // x = mode (0=multiply,1=screen,2=overlay), yz = taille écran
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 fragPos;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        // même formule SDF boîte-arrondie par coin que RECT_FRAGMENT_SRC — voir sa javadoc/commentaire pour le détail.
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 p = fragPos - center;\n" +
        "    float radius = (p.x > 0.0)\n" +
        "        ? ((p.y < 0.0) ? u_CornerRadii.y : u_CornerRadii.w)\n" +
        "        : ((p.y < 0.0) ? u_CornerRadii.x : u_CornerRadii.z);\n" +
        "    vec2 q = abs(p) - halfSize + vec2(radius);\n" +
        "    float dist = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - radius;\n" +
        "    float shapeAlpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    vec2 screenUV = fragPos / u_ModeAndScreen.yz;\n" +
        "    vec3 dst = texture(Sampler0, screenUV).rgb;\n" +
        "    vec3 top = u_TopColor.rgb;\n" +
        "    int mode = int(u_ModeAndScreen.x + 0.5);\n" +
        "    vec3 blended;\n" +
        "    if (mode == 0) {\n" +
        "        blended = top * dst;\n" +
        "    } else if (mode == 1) {\n" +
        "        blended = vec3(1.0) - (vec3(1.0) - top) * (vec3(1.0) - dst);\n" +
        "    } else {\n" +
        "        vec3 multiplyBranch = 2.0 * top * dst;\n" +
        "        vec3 screenBranch = vec3(1.0) - 2.0 * (vec3(1.0) - top) * (vec3(1.0) - dst);\n" +
        "        blended = mix(multiplyBranch, screenBranch, step(vec3(0.5), dst));\n" +
        "    }\n" +
        "    vec4 color = vec4(blended, 1.0) * vertexColor * ColorModulator;\n" +
        "    color.a *= shapeAlpha * u_TopColor.a;\n" +
        "    if (color.a < 0.01) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    static Object blendPipeline, blendShaderSource;
    static Object blendParamsBuffer;

    static boolean resolveBlendPipeline() {
        try {
            Object vId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_blend.vsh");
            Object fId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_blend.fsh");
            blendPipeline = gpu.buildPipeline("ui_blaze3d_blend", vId, fId,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection", "RectParams", "BlendParams" }, null, null);
            blendShaderSource = gpu.shaderSource(vId, RECT_VERTEX_SRC, fId, BLEND_FRAGMENT_SRC);
            return blendPipeline != null;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[UiRenderer] Blaze3DBlend: résolution pipeline échouée : " + t + " | cause réelle : " + cause);
            return false;
        }
    }

    // ── Instantané du framebuffer (obligatoire avant tout blend) ────────────
    //
    // BUG ÉVITÉ (pas rencontré en jeu, repéré à la revue) : lire ET écrire la
    // MÊME texture (colorView, la cible de rendu réelle) dans la MÊME passe
    // est un hasard non défini sur toute API GPU moderne (aucune garantie sur
    // quelle valeur — ancienne ou tout juste écrite — un sample lirait). Même
    // précaution déjà appliquée dans Blaze3DBlur (jamais lu la cible en cours
    // d'écriture) : on capture d'abord colorView dans une texture SÉPARÉE
    // (pleine résolution, simple copie via homePipeline — pas de flou ici,
    // juste un passthrough) puis le shader de fusion lit CETTE copie, jamais
    // colorView directement.

    private static Object snapshotTexture, snapshotView;
    private static int snapshotVpWidth = -1, snapshotVpHeight = -1;

    private static void ensureSnapshotTexture(Object device, int vpWidth, int vpHeight) throws Exception {
        if (snapshotVpWidth == vpWidth && snapshotVpHeight == vpHeight && snapshotTexture != null) return;
        if (snapshotTexture != null) {
            // Journalisé, jamais avalé (voir Blaze3DBlur.ensureChain).
            try {
                gpu.closeTexture(snapshotTexture);
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] Blaze3DBlend: fermeture de l'instantané échouée : " + t);
            }
        }
        int usage = gpu.usageTextureBinding() | gpu.usageTextureRenderAttachment();
        snapshotTexture = gpu.createTexture(device, "yuyuframe_blend_snapshot", usage, vpWidth, vpHeight, 1);
        snapshotView = gpu.createTextureView(device, snapshotTexture);
        snapshotVpWidth = vpWidth;
        snapshotVpHeight = vpHeight;
    }

    /** Copie {@code sourceColorView} (le framebuffer réel) dans la texture d'instantané — un seul quad plein écran via {@code homePipeline} (texture×couleur, déjà existant, aucun nouveau shader). */
    private static void captureSnapshot(Object device, Object encoder, Object sourceColorView, int vpWidth, int vpHeight) throws Exception {
        ensureSnapshotTexture(device, vpWidth, vpHeight);
        Object[] white = ensureWhiteTexture();
        Object sampler = ensureBackdropSampler(device);

        int rgba = 0xFFFFFFFF;
        short light0 = 0, light1 = 0;
        ByteBuffer verts = ensureStagingBuffer(4 * 28);
        putVertexPCTL(verts, 0f, (float) vpHeight, rgba, 0f, 0f, light0, light1);
        putVertexPCTL(verts, 0f, 0f, rgba, 0f, 1f, light0, light1);
        putVertexPCTL(verts, (float) vpWidth, 0f, rgba, 1f, 1f, light0, light1);
        putVertexPCTL(verts, (float) vpWidth, (float) vpHeight, rgba, 1f, 0f, light0, light1);
        verts.flip();

        Object vbo = ensureVertexBuffer(device, verts.remaining());
        gpu.write(encoder, gpu.slice(vbo, 0L, verts.remaining()), verts);

        Object dynSlice = gpu.dynamicTransforms(1f, 1f, 1f, 1f);

        Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
        Object projectionSlice = gpu.slice(projectionBuf, 0L, 64L);

        Object pass = gpu.openPass(encoder, "yuyuframe_blend_snapshot_copy", snapshotView);
        try {
            gpu.precompile(device, homePipeline, homeShaderSource);
            gpu.setPipeline(pass, homePipeline);
            gpu.disableScissor(pass);
            gpu.bindDefaultUniforms(pass);
            gpu.setUniform(pass, "Projection", projectionSlice);
            gpu.setUniform(pass, "DynamicTransforms", dynSlice);
            gpu.bindTexture(pass, "Sampler0", sourceColorView, sampler);
            gpu.bindTexture(pass, "Sampler2", white[1], white[2]);
            gpu.setVertexBuffer(pass, 0, vbo);
            gpu.drawQuads(pass, 1);
        } finally {
            gpu.closePass(pass);
        }
    }

    static Object ensureBlendParamsBuffer(Object device) throws Exception {
        if (blendParamsBuffer == null) {
            blendParamsBuffer = gpu.createBuffer(device, "yuyuframe_blend_params",
                gpu.usageBufferUniform() | gpu.usageBufferCopyDst(), 32L);
        }
        return blendParamsBuffer;
    }

    static Object writeBlendParams(Object device, Object encoder, UiColor top, int mode, float screenW, float screenH) throws Exception {
        Object buffer = ensureBlendParamsBuffer(device);
        ByteBuffer data = scratch(32);
        data.putFloat(top.r).putFloat(top.g).putFloat(top.b).putFloat(top.a);
        data.putFloat((float) mode).putFloat(screenW).putFloat(screenH).putFloat(0f);
        data.flip();
        Object slice = gpu.slice(buffer, 0L, 32L);
        gpu.write(encoder, slice, data);
        return slice;
    }

    /**
     * Empile un rect à mode de fusion — {@code mode} = {@link #MODE_MULTIPLY}/{@link #MODE_SCREEN}/{@link #MODE_OVERLAY}, {@code topColor} = couleur "au-dessus" mélangée avec ce qui est actuellement affiché derrière, coins arrondis par coin (même convention que {@link Blaze3DRect#queueRect}).
     */
    public static void queueBlendRect(float x0, float y0, float x1, float y1,
                                       float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                       UiColor topColor, int mode, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawBlendRect(x0, y0, x1, y1, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight, topColor, mode, vpWidth, vpHeight));
    }

    private static boolean drawBlendRect(float x0, float y0, float x1, float y1,
                                          float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                          UiColor topColor, int mode, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "mainColorView(blendrect)";
            Object colorView = gpu.mainColorView();
            if (colorView == null) return false;

            float maxR = Math.min((x1 - x0) / 2f, (y1 - y0) / 2f);
            float rTL = Math.max(0f, Math.min(radiusTopLeft, maxR));
            float rTR = Math.max(0f, Math.min(radiusTopRight, maxR));
            float rBL = Math.max(0f, Math.min(radiusBottomLeft, maxR));
            float rBR = Math.max(0f, Math.min(radiusBottomRight, maxR));

            currentStage = "device(blendrect)";
            Object device = gpu.device();
            currentStage = "encoder(blendrect)";
            Object encoder = gpu.encoder(device);

            int rgba = 0xFFFFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(blendrect)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            gpu.write(encoder, gpu.slice(vbo, 0L, verts.remaining()), verts);

            currentStage = "dynamicTransforms(blendrect)";
            Object dynSlice = gpu.dynamicTransforms(1f, 1f, 1f, 1f);

            currentStage = "ensureProjectionBuffer(blendrect)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = gpu.slice(projectionBuf, 0L, 64L);

            currentStage = "writeRectParams(blendrect)";
            Object rectParamsSlice = writeRectParams(device, encoder, x0, y0, x1, y1, rTL, rTR, rBL, rBR);

            currentStage = "writeBlendParams(blendrect)";
            Object blendParamsSlice = writeBlendParams(device, encoder, topColor, mode, (float) vpWidth, (float) vpHeight);

            currentStage = "captureSnapshot(blendrect)";
            captureSnapshot(device, encoder, colorView, vpWidth, vpHeight);

            currentStage = "openPass(blendrect)";
            Object pass = gpu.openPass(encoder, "yuyuframe_blendrect", colorView);
            try {
                currentStage = "setPipeline(blendrect)";
                gpu.precompile(device, blendPipeline, blendShaderSource);
                gpu.setPipeline(pass, blendPipeline);
                currentStage = "disableScissor(blendrect)";
                gpu.disableScissor(pass);
                currentStage = "bindDefaultUniforms(blendrect)";
                gpu.bindDefaultUniforms(pass);
                currentStage = "setUniform(Projection)(blendrect)";
                gpu.setUniform(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(blendrect)";
                gpu.setUniform(pass, "DynamicTransforms", dynSlice);
                currentStage = "setUniform(RectParams)(blendrect)";
                gpu.setUniform(pass, "RectParams", rectParamsSlice);
                currentStage = "setUniform(BlendParams)(blendrect)";
                gpu.setUniform(pass, "BlendParams", blendParamsSlice);
                currentStage = "bindTexture(Sampler0)(blendrect)";
                gpu.bindTexture(pass, "Sampler0", snapshotView, ensureBackdropSampler(device));
                currentStage = "setVertexBuffer(blendrect)";
                gpu.setVertexBuffer(pass, 0, vbo);
                currentStage = "drawQuads(blendrect)";
                gpu.drawQuads(pass, 1);
            } finally {
                gpu.closePass(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] Blaze3DBlend.drawBlendRect a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    private static Object backdropSampler;
    private static Object ensureBackdropSampler(Object device) throws Exception {
        if (backdropSampler == null) {
            backdropSampler = gpu.linearSampler();
        }
        return backdropSampler;
    }
}
