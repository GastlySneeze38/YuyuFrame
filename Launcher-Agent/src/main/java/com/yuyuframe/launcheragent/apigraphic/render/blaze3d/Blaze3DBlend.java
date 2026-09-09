package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import static com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore.*;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

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
            Object vId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blend.vsh");
            Object fId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blend.fsh");
            blendPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_blend", vId, fId,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection", "RectParams", "BlendParams" });
            blendShaderSource = ShaderPipelineFactory.shaderSource(vId, RECT_VERTEX_SRC, fId, BLEND_FRAGMENT_SRC);
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
            try { mCloseTexture.invoke(snapshotTexture); } catch (Throwable ignored) {}
        }
        java.util.function.Supplier<String> label = () -> "yuyuframe_blend_snapshot";
        int usage = usageTextureBinding | usageTextureRenderAttachment;
        snapshotTexture = mCreateTexture.invoke(device, label, usage, fieldTextureFormatRgba8, vpWidth, vpHeight, 1, 1);
        snapshotView = mCreateTextureView.invoke(device, snapshotTexture);
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
        Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
        mWriteToBuffer.invoke(encoder, slice, verts);

        Object identity4 = identityMatrix4f();
        Object white4 = ctorVector4f.newInstance(1f, 1f, 1f, 1f);
        Object zero3 = zeroVector3f();
        Object dynUniforms = mGetDynamicUniforms.invoke(null);
        Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, white4, zero3, identity4);

        Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
        Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

        java.util.function.Supplier<String> passLabel = () -> "yuyuframe_blend_snapshot_copy";
        Object pass = mCreateRenderPass.invoke(encoder, passLabel, snapshotView, OptionalInt.empty());
        try {
            ShaderPipelineFactory.precompile(device, homePipeline, homeShaderSource);
            mSetPipeline.invoke(pass, homePipeline);
            if (mDisableScissor != null) mDisableScissor.invoke(pass);
            mBindDefaultUniforms.invoke(null, pass);
            mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
            mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
            mBindTexture.invoke(pass, "Sampler0", sourceColorView, sampler);
            mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
            mSetVertexBuffer.invoke(pass, 0, vbo);
            if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
            Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
            Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
            mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
            mDrawIndexed.invoke(pass, 0, 0, 6, 1);
        } finally {
            mClosePass.invoke(pass);
        }
    }

    static Object ensureBlendParamsBuffer(Object device) throws Exception {
        if (blendParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_blend_params";
            blendParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 32L);
        }
        return blendParamsBuffer;
    }

    static Object writeBlendParams(Object device, Object encoder, UiColor top, int mode, float screenW, float screenH) throws Exception {
        Object buffer = ensureBlendParamsBuffer(device);
        ByteBuffer data = scratch(32);
        data.putFloat(top.r).putFloat(top.g).putFloat(top.b).putFloat(top.a);
        data.putFloat((float) mode).putFloat(screenW).putFloat(screenH).putFloat(0f);
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 32L);
        mWriteToBuffer.invoke(encoder, slice, data);
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
            currentStage = "minecraftClient(blendrect)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(blendrect)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(blendrect)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float maxR = Math.min((x1 - x0) / 2f, (y1 - y0) / 2f);
            float rTL = Math.max(0f, Math.min(radiusTopLeft, maxR));
            float rTR = Math.max(0f, Math.min(radiusTopRight, maxR));
            float rBL = Math.max(0f, Math.min(radiusBottomLeft, maxR));
            float rBR = Math.max(0f, Math.min(radiusBottomRight, maxR));

            currentStage = "getDevice(blendrect)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(blendrect)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(blendrect)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(blendrect)";
            Object identity4 = identityMatrix4f();
            Object colorMod = ctorVector4f.newInstance(1f, 1f, 1f, 1f);
            Object zero3 = zeroVector3f();
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, colorMod, zero3, identity4);

            currentStage = "ensureProjectionBuffer(blendrect)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "writeRectParams(blendrect)";
            Object rectParamsSlice = writeRectParams(device, encoder, x0, y0, x1, y1, rTL, rTR, rBL, rBR);

            currentStage = "writeBlendParams(blendrect)";
            Object blendParamsSlice = writeBlendParams(device, encoder, topColor, mode, (float) vpWidth, (float) vpHeight);

            currentStage = "captureSnapshot(blendrect)";
            captureSnapshot(device, encoder, colorView, vpWidth, vpHeight);

            currentStage = "createRenderPass(blendrect)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_blendrect";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(blendrect)";
                ShaderPipelineFactory.precompile(device, blendPipeline, blendShaderSource);
                mSetPipeline.invoke(pass, blendPipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(blendrect)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(blendrect)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(blendrect)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(blendrect)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "setUniform(RectParams)(blendrect)";
                mSetUniformSlice.invoke(pass, "RectParams", rectParamsSlice);
                currentStage = "setUniform(BlendParams)(blendrect)";
                mSetUniformSlice.invoke(pass, "BlendParams", blendParamsSlice);
                currentStage = "bindTexture(Sampler0)(blendrect)";
                mBindTexture.invoke(pass, "Sampler0", snapshotView, ensureBackdropSampler(device));
                currentStage = "setVertexBuffer(blendrect)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                mDrawIndexed.invoke(pass, 0, 0, 6, 1);
            } finally {
                mClosePass.invoke(pass);
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
            backdropSampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        }
        return backdropSampler;
    }

    // ── POC ──────────────────────────────────────────────────────────────
    /** Toggle via {@code /yf blendpoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean testEnabled = false;

    /** 3 panneaux côte à côte (multiply/screen/overlay) sur le même fond — preuve de mécanisme, dessiné en direct. */
    public static void drawTestPanels(int vpWidth, int vpHeight) {
        float w = 160f, h = 160f, gap = 20f, y0 = vpHeight - 500f;
        float x0 = 40f;
        UiColor orange = new UiColor(255, 140, 0, 255);
        drawBlendRect(x0, y0, x0 + w, y0 + h, 12f, 12f, 12f, 12f, orange, MODE_MULTIPLY, vpWidth, vpHeight);
        drawBlendRect(x0 + (w + gap), y0, x0 + (w + gap) + w, y0 + h, 12f, 12f, 12f, 12f, orange, MODE_SCREEN, vpWidth, vpHeight);
        drawBlendRect(x0 + 2 * (w + gap), y0, x0 + 2 * (w + gap) + w, y0 + h, 12f, 12f, 12f, 12f, orange, MODE_OVERLAY, vpWidth, vpHeight);
    }
}
