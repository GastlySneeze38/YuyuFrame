package com.yuyuframe.launcheragent.apigraphic.shader;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Preuve de mécanisme (roadmap Phase 5, "pipeline shader maison" — voir
 * {@link ShaderPipelineFactory} pour le contexte complet) : dessine UN quad
 * de couleur plate via un {@code RenderPipeline} ET un GLSL ENTIÈREMENT
 * maison, sans jamais toucher à {@code RenderPipelines.GUI_TEXT} pour le
 * rendu réel (seulement pour COPIER son format de sommets/état GPU au
 * moment de construire notre pipeline, voir {@code ShaderPipelineFactory}).
 *
 * Isolé/jetable — n'est branché sur AUCUN draw call de l'UI existante
 * ({@code UiPrimitiveRenderer}/{@code UiTextRenderer}/{@code UiTextBlaze3D}
 * continuent à utiliser {@code GUI_TEXT} normalement). Déclenché uniquement
 * par {@code /yf shaderpoc} (voir {@code YfCommands}), pour vérifier
 * visuellement en jeu que {@code GpuDevice.precompilePipeline(RenderPipeline,
 * ShaderSource)} fonctionne bien avec du GLSL 100% maison sur ce bracket
 * AVANT de migrer quoi que ce soit de réel dessus.
 *
 * GLSL volontairement SANS uniform : positions codées en dur en NDC dans le
 * vertex shader (indexées via {@code gl_VertexID}), couleur plate en sortie
 * du fragment shader — isole le test au seul mécanisme "précompiler+dessiner
 * avec notre propre GLSL", sans entraîner la machinerie DynamicTransforms/
 * Projection existante. Un vrai vertex buffer (4 sommets, même disposition
 * 28 octets que {@code RenderPipelines.GUI_TEXT} — position/couleur/uv/light,
 * voir {@code UiTextBlaze3D.putSolidQuad}) reste bindé malgré tout : notre
 * shader ignore son contenu, mais le lier respecte le contrat de binding du
 * pipeline (même {@code VertexFormat} que GUI_TEXT, copié tel quel).
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

    // ── Résolution réflexion (draw-call minimal, même conventions que UiTextBlaze3D) ──

    private static boolean resolveAttempted, resolveOk;
    private static String currentStage = "";

    private static Class<?> clsRenderSystem, clsCommandEncoder, clsRenderPass, clsGpuBuffer, clsGpuBufferSlice,
        clsFramebuffer, clsGpuTextureView;

    private static Method mCreateCommandEncoder, mCreateBufferSized, mBufferSlice, mWriteToBuffer,
        mCreateRenderPass, mSetPipeline, mSetVertexBuffer, mSetIndexBuffer, mDrawIndexed, mClosePass,
        mDisableScissor, mGetColorAttachmentView, mGetFramebuffer,
        mShapeIndexBufferGetBuffer, mShapeIndexBufferGetType;

    private static java.lang.reflect.Field fieldSharedSequentialQuad;
    private static Object sharedSequentialQuad;

    private static int usageBufferVertex, usageBufferCopyDst;

    private static Object pipeline;
    private static Object shaderSource;
    private static Object vertexBuffer;

    private static synchronized boolean resolveDrawCall() {
        if (resolveAttempted) return resolveOk;
        resolveAttempted = true;
        try {
            currentStage = "rawClass";
            clsRenderSystem = McReflect.rawClass("com.mojang.blaze3d.systems.RenderSystem");
            clsCommandEncoder = McReflect.rawClass("com.mojang.blaze3d.systems.CommandEncoder");
            clsRenderPass = McReflect.rawClass("com.mojang.blaze3d.systems.RenderPass");
            clsGpuBuffer = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBuffer");
            clsGpuBufferSlice = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBufferSlice");
            clsGpuTextureView = McReflect.rawClass("com.mojang.blaze3d.textures.GpuTextureView");
            Class<?> clsGpuDevice = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice");
            clsFramebuffer = McReflect.yarnClass("net/minecraft/client/gl/Framebuffer", "com.mojang.blaze3d.pipeline.RenderTarget");
            if (clsRenderSystem == null || clsCommandEncoder == null || clsRenderPass == null || clsGpuBuffer == null
                    || clsGpuBufferSlice == null || clsGpuTextureView == null || clsGpuDevice == null || clsFramebuffer == null) {
                throw new ClassNotFoundException("une classe Blaze3D/Framebuffer requise est introuvable");
            }

            currentStage = "methods";
            mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
            mCreateBufferSized = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, long.class);
            mBufferSlice = clsGpuBuffer.getMethod("slice", long.class, long.class);
            mWriteToBuffer = clsCommandEncoder.getMethod("writeToBuffer", clsGpuBufferSlice, ByteBuffer.class);
            mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass",
                java.util.function.Supplier.class, clsGpuTextureView, OptionalInt.class);
            mSetPipeline = clsRenderPass.getMethod("setPipeline", McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline"));
            mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
            mClosePass = clsRenderPass.getMethod("close");
            try {
                mDisableScissor = clsRenderPass.getMethod("disableScissor");
            } catch (NoSuchMethodException noScissorApi) {
                mDisableScissor = null;
            }

            // Index de triangulation partagé — même utilitaire/mêmes contraintes
            // que UiTextBlaze3D (RenderPipelines.GUI_TEXT, dont on copie le
            // VertexFormatMode QUADS, exige un draw INDEXÉ — voir son
            // commentaire de classe pour le détail complet du bug historique).
            fieldSharedSequentialQuad = clsRenderSystem.getDeclaredField("sharedSequentialQuad");
            fieldSharedSequentialQuad.setAccessible(true);
            Class<?> clsShapeIndexBuffer = fieldSharedSequentialQuad.getType();
            mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod("getBuffer", int.class);
            mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod("type");
            Class<?> clsIndexType = mShapeIndexBufferGetType.getReturnType();
            mSetIndexBuffer = clsRenderPass.getMethod("setIndexBuffer", clsGpuBuffer, clsIndexType);
            mDrawIndexed = clsRenderPass.getMethod("drawIndexed", int.class, int.class, int.class, int.class);

            mGetColorAttachmentView = MappingsRegistry.isLoaded()
                ? clsFramebuffer.getMethod(MappingsRegistry.getObfMethodName("net/minecraft/client/gl/Framebuffer", "getColorAttachmentView"))
                : clsFramebuffer.getMethod("getColorTextureView");

            usageBufferVertex = clsGpuBuffer.getField("USAGE_VERTEX").getInt(null);
            usageBufferCopyDst = clsGpuBuffer.getField("USAGE_COPY_DST").getInt(null);

            resolveOk = true;
        } catch (Throwable t) {
            resolveOk = false;
            LauncherLog.err("[LauncherAgent] UiSolidPipelinePoc: résolution échouée à l'étape '" + currentStage + "' : " + t);
        }
        return resolveOk;
    }

    private static Object getFramebuffer(Object mc) throws Exception {
        if (mGetFramebuffer == null) {
            String name = MappingsRegistry.isLoaded()
                ? MappingsRegistry.getObfMethodName("net/minecraft/client/MinecraftClient", "getFramebuffer")
                : "getMainRenderTarget";
            mGetFramebuffer = mc.getClass().getMethod(name);
        }
        return mGetFramebuffer.invoke(mc);
    }

    /** 28 octets/sommet — même disposition que {@code RenderPipelines.GUI_TEXT} (position/couleur/uv/light) ; notre shader ignore tout sauf {@code gl_VertexID}, seul le contrat de binding importe ici. */
    private static void putVertex(ByteBuffer buf, float x, float y) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        buf.putFloat(0f).putFloat(0f);
        buf.putShort((short) 0).putShort((short) 0);
    }

    /** Dessine un quad de couleur plate via notre pipeline maison — {@code true} si le draw a réussi, {@code false}/log sinon (voir {@code currentStage} dans les logs pour l'étape exacte). */
    public static boolean drawTestQuad() {
        if (!ShaderPipelineFactory.isAvailable() || !resolveDrawCall()) return false;
        try {
            currentStage = "device";
            Object device = ShaderPipelineFactory.device();

            if (pipeline == null) {
                currentStage = "identifiers";
                Object vertexId = ShaderPipelineFactory.identifier("yuyuframe", "shader/poc_solid.vsh");
                Object fragmentId = ShaderPipelineFactory.identifier("yuyuframe", "shader/poc_solid.fsh");
                currentStage = "buildPipeline";
                pipeline = ShaderPipelineFactory.buildPipeline("poc_solid", vertexId, fragmentId);
                currentStage = "shaderSource";
                shaderSource = ShaderPipelineFactory.shaderSource(vertexId, VERTEX_SRC, fragmentId, FRAGMENT_SRC);
                LauncherLog.ui(1, "[LauncherAgent] UiSolidPipelinePoc: pipeline maison construit avec succès (voir /yf shaderpoc)");
            }
            // Vérifié contre UniversalCraft (URenderPipeline.kt, précédent
            // direct pour ce même mécanisme sur cette ère Blaze3D) : ils
            // rappellent precompilePipeline(pipeline, source) à CHAQUE draw,
            // pas une seule fois à la construction — leur commentaire :
            // "need to do this each draw (it'll no-op if it's already
            // cached) because resource reloads will clear it again". Un
            // rechargement de ressources (F3+T, changement de resource pack)
            // vide le cache de pipelines du device ; sans ce re-appel, notre
            // pipeline resterait silencieusement "oublié" après un tel
            // événement alors que setPipeline() continuerait à le référencer.
            currentStage = "precompile";
            ShaderPipelineFactory.precompile(device, pipeline, shaderSource);

            currentStage = "minecraftClient";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer";
            Object fb = getFramebuffer(mc);
            if (fb == null) return false;
            currentStage = "getColorAttachmentView";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            currentStage = "createCommandEncoder";
            Object encoder = mCreateCommandEncoder.invoke(device);

            if (vertexBuffer == null) {
                currentStage = "createVertexBuffer";
                java.util.function.Supplier<String> label = () -> "yuyuframe_shaderpoc_vbo";
                vertexBuffer = mCreateBufferSized.invoke(device, label, usageBufferVertex | usageBufferCopyDst, 4L * 28L);
            }
            ByteBuffer verts = ByteBuffer.allocateDirect(4 * 28).order(java.nio.ByteOrder.nativeOrder());
            putVertex(verts, -0.3f, -0.3f);
            putVertex(verts, 0.3f, -0.3f);
            putVertex(verts, -0.3f, 0.3f);
            putVertex(verts, 0.3f, 0.3f);
            verts.flip();
            currentStage = "writeToBuffer";
            Object slice = mBufferSlice.invoke(vertexBuffer, 0L, (long) verts.remaining());
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "createRenderPass";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_shaderpoc";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline";
                mSetPipeline.invoke(pass, pipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor"; mDisableScissor.invoke(pass); }
                currentStage = "setVertexBuffer";
                mSetVertexBuffer.invoke(pass, 0, vertexBuffer);

                currentStage = "shapeIndexBuffer";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed";
                mDrawIndexed.invoke(pass, 0, 0, 6, 1);
            } finally {
                currentStage = "closePass";
                mClosePass.invoke(pass);
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
