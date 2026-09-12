package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * {@link Blaze3DGpu} pour la 26.1.2 — TRANSITOIRE, par réflexion.
 *
 * <h2>Pourquoi ce fichier existe encore</h2>
 *
 * Retrait de la réflexion du moteur, étape 2b : le moteur ne parle plus qu'à
 * {@link Blaze3DGpu}. La 1.21.11 a son implémentation typée ({@code
 * Blaze3DGpu1211}) ; la 26.1.2 aura la sienne à l'étape 3. En attendant, TOUTE
 * la réflexion Blaze3D qui restait éparpillée dans {@code Blaze3DCore}, {@code
 * ShaderPipelineFactory} et les cinq satellites est regroupée ICI, et nulle part
 * ailleurs — mêmes appels, mêmes noms (vérifiés par {@code javap} sur le jar
 * client 26.1.2 réel lors de leur écriture d'origine), simplement déplacés.
 *
 * <p>À SUPPRIMER à l'étape 3, quand {@code Blaze3DGpu261} typé existera.
 *
 * <p>Choisi par {@link Blaze3DGpus#active()} seulement si aucune implémentation
 * typée ne correspond à la version. Noms 26.1.2 uniquement (jeu non obfusqué) :
 * les branches Yarn de l'ancien code ne servaient à rien — la fabrique de
 * pipelines exigeait {@code ColorTargetState}, absente de tout bracket Yarn.
 */
final class Blaze3DGpuReflective261 implements Blaze3DGpu {

    private final Method mGetDevice, mGetSamplerCache, mSamplerGetClampToEdge, mBindDefaultUniforms,
        mGetDynamicUniforms, mDynamicUniformsWrite,
        mCreateCommandEncoder, mCreateTexture, mCreateTextureView, mCreateBuffer, mBufferSlice,
        mWriteToTexture, mWriteToTextureRegion, mWriteToBuffer, mCreateRenderPass,
        mSetPipeline, mBindTexture, mSetUniform, mSetVertexBuffer, mClosePass, mDisableScissor,
        mSetIndexBuffer, mDrawIndexed, mIndexGetBuffer, mIndexType,
        mCloseTexture, mSetPixelAbgr, mGetMainRenderTarget, mGetColorTextureView, mIdentifierOf,
        mBuilderStatic, mWithLocation, mWithVertexShader, mWithFragmentShader, mWithVertexFormat,
        mWithColorTargetState, mWithDepthStencilState, mWithCull, mWithSampler, mWithUniform, mBuild,
        mPrecompilePipeline, mGetVertexFormat, mGetVertexFormatMode, mGetColorTargetState,
        mGetDepthStencilState, mIsCull;

    private final Field fieldSharedSequentialQuad;
    private final Constructor<?> ctorNativeImage, ctorVector4f;
    private final Class<?> clsSnippet, clsShaderSource;
    private final Object formatRgba8, filterLinear, nativeFormatRgba, uniformBufferType, guiText, identity4, zero3;
    private final int usageBufferVertex, usageBufferUniform, usageBufferCopyDst,
        usageTextureBinding, usageTextureCopyDst, usageTextureRenderAttachment;

    private Object linearSampler;

    /** @return l'adaptateur, ou {@code null} (journalisé) si la résolution échoue. */
    static Blaze3DGpu createOrNull() {
        try {
            return new Blaze3DGpuReflective261();
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[Blaze3DGpuReflective261] résolution échouée : " + t + " | cause réelle : " + cause);
            return null;
        }
    }

    private Blaze3DGpuReflective261() throws Exception {
        Class<?> clsRenderSystem = raw("com.mojang.blaze3d.systems.RenderSystem");
        Class<?> clsGpuDevice = raw("com.mojang.blaze3d.systems.GpuDevice");
        Class<?> clsCommandEncoder = raw("com.mojang.blaze3d.systems.CommandEncoder");
        Class<?> clsRenderPass = raw("com.mojang.blaze3d.systems.RenderPass");
        Class<?> clsGpuTexture = raw("com.mojang.blaze3d.textures.GpuTexture");
        Class<?> clsGpuTextureView = raw("com.mojang.blaze3d.textures.GpuTextureView");
        Class<?> clsTextureFormat = raw("com.mojang.blaze3d.textures.TextureFormat");
        Class<?> clsFilterMode = raw("com.mojang.blaze3d.textures.FilterMode");
        Class<?> clsGpuBuffer = raw("com.mojang.blaze3d.buffers.GpuBuffer");
        Class<?> clsGpuBufferSlice = raw("com.mojang.blaze3d.buffers.GpuBufferSlice");
        Class<?> clsRenderPipeline = raw("com.mojang.blaze3d.pipeline.RenderPipeline");
        Class<?> clsBuilder = raw("com.mojang.blaze3d.pipeline.RenderPipeline$Builder");
        clsSnippet = raw("com.mojang.blaze3d.pipeline.RenderPipeline$Snippet");
        Class<?> clsVertexFormat = raw("com.mojang.blaze3d.vertex.VertexFormat");
        Class<?> clsVertexFormatMode = raw("com.mojang.blaze3d.vertex.VertexFormat$Mode");
        Class<?> clsColorTargetState = raw("com.mojang.blaze3d.pipeline.ColorTargetState");
        Class<?> clsDepthStencilState = raw("com.mojang.blaze3d.pipeline.DepthStencilState");
        clsShaderSource = raw("com.mojang.blaze3d.shaders.ShaderSource");
        Class<?> clsUniformType = raw("com.mojang.blaze3d.shaders.UniformType");
        Class<?> clsSamplerCache = raw("com.mojang.blaze3d.systems.SamplerCache");
        Class<?> clsNativeImage = raw("com.mojang.blaze3d.platform.NativeImage");
        Class<?> clsNativeImageFormat = raw("com.mojang.blaze3d.platform.NativeImage$Format");
        Class<?> clsRenderTarget = raw("com.mojang.blaze3d.pipeline.RenderTarget");
        Class<?> clsMinecraft = raw("net.minecraft.client.Minecraft");
        Class<?> clsIdentifier = raw("net.minecraft.resources.Identifier");
        Class<?> clsRenderPipelines = raw("net.minecraft.client.renderer.RenderPipelines");
        Class<?> clsDynamicUniforms = raw("net.minecraft.client.renderer.DynamicUniforms");
        Class<?> clsMatrix4f = raw("org.joml.Matrix4f");
        Class<?> clsVector3f = raw("org.joml.Vector3f");
        Class<?> clsVector4f = raw("org.joml.Vector4f");

        mGetDevice = clsRenderSystem.getMethod("getDevice");
        mGetSamplerCache = clsRenderSystem.getMethod("getSamplerCache");
        mBindDefaultUniforms = clsRenderSystem.getMethod("bindDefaultUniforms", clsRenderPass);
        mGetDynamicUniforms = clsRenderSystem.getMethod("getDynamicUniforms");
        // getClampToEdge(FilterMode, true) : le booléen à true garde la plage de
        // LOD complète (mips utilisés) — la surcharge sans booléen fige le LOD à
        // 0, bug historique du texte « pixelisé ».
        mSamplerGetClampToEdge = clsSamplerCache.getMethod("getClampToEdge", clsFilterMode, boolean.class);

        mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
        mCreateTexture = clsGpuDevice.getMethod("createTexture",
            Supplier.class, int.class, clsTextureFormat, int.class, int.class, int.class, int.class);
        mCreateTextureView = clsGpuDevice.getMethod("createTextureView", clsGpuTexture);
        mCreateBuffer = clsGpuDevice.getMethod("createBuffer", Supplier.class, int.class, long.class);
        mPrecompilePipeline = clsGpuDevice.getMethod("precompilePipeline", clsRenderPipeline, clsShaderSource);
        mBufferSlice = clsGpuBuffer.getMethod("slice", long.class, long.class);

        mWriteToTexture = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage);
        mWriteToTextureRegion = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage,
            int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        mWriteToBuffer = clsCommandEncoder.getMethod("writeToBuffer", clsGpuBufferSlice, ByteBuffer.class);
        mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass", Supplier.class, clsGpuTextureView, OptionalInt.class);

        mSetPipeline = clsRenderPass.getMethod("setPipeline", clsRenderPipeline);
        mBindTexture = clsRenderPass.getMethod("bindTexture", String.class, clsGpuTextureView,
            raw("com.mojang.blaze3d.textures.GpuSampler"));
        mSetUniform = clsRenderPass.getMethod("setUniform", String.class, clsGpuBufferSlice);
        mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
        mClosePass = clsRenderPass.getMethod("close");
        mDisableScissor = clsRenderPass.getMethod("disableScissor");
        mDrawIndexed = clsRenderPass.getMethod("drawIndexed", int.class, int.class, int.class, int.class);

        // Tampon d'indices séquentiel partagé (QUADS → triangles) : champ privé
        // RenderSystem.sharedSequentialQuad. Classe imbriquée DÉRIVÉE du type du
        // champ plutôt que nommée (son nom n'est garanti stable nulle part).
        fieldSharedSequentialQuad = clsRenderSystem.getDeclaredField("sharedSequentialQuad");
        fieldSharedSequentialQuad.setAccessible(true);
        Class<?> clsIndexBuffer = fieldSharedSequentialQuad.getType();
        mIndexGetBuffer = clsIndexBuffer.getMethod("getBuffer", int.class);
        mIndexType = clsIndexBuffer.getMethod("type");
        mSetIndexBuffer = clsRenderPass.getMethod("setIndexBuffer", clsGpuBuffer, mIndexType.getReturnType());

        // DynamicUniforms.write(Matrix4fc, Vector4fc, Vector3fc, Matrix4fc) — seule
        // méthode à 4 paramètres renvoyant une GpuBufferSlice.
        Method write = null;
        for (Method m : clsDynamicUniforms.getMethods()) {
            if (m.getParameterCount() == 4 && clsGpuBufferSlice.isAssignableFrom(m.getReturnType())) {
                write = m;
                break;
            }
        }
        if (write == null) throw new NoSuchMethodException("DynamicUniforms.write(4 paramètres)");
        mDynamicUniformsWrite = write;

        mGetMainRenderTarget = clsMinecraft.getMethod("getMainRenderTarget");
        mGetColorTextureView = clsRenderTarget.getMethod("getColorTextureView");

        ctorNativeImage = clsNativeImage.getDeclaredConstructor(clsNativeImageFormat, int.class, int.class, boolean.class);
        ctorNativeImage.setAccessible(true);
        // Format ABGR packé ((a<<24)|(b<<16)|(g<<8)|r) : exactement ce que le nom
        // setPixelABGR décrit (setColor n'existe plus en 26.1).
        mSetPixelAbgr = clsNativeImage.getMethod("setPixelABGR", int.class, int.class, int.class);
        Field rgba = clsNativeImageFormat.getDeclaredField("RGBA");
        rgba.setAccessible(true);
        nativeFormatRgba = rgba.get(null);

        formatRgba8 = clsTextureFormat.getField("RGBA8").get(null);
        filterLinear = clsFilterMode.getField("LINEAR").get(null);
        mCloseTexture = clsGpuTexture.getMethod("close");

        // Constantes DISTINCTES de GpuTexture et GpuBuffer (même nom de champ,
        // classes différentes) — voir le bug historique USAGE_COPY_DST.
        usageTextureBinding = clsGpuTexture.getField("USAGE_TEXTURE_BINDING").getInt(null);
        usageTextureCopyDst = clsGpuTexture.getField("USAGE_COPY_DST").getInt(null);
        usageTextureRenderAttachment = clsGpuTexture.getField("USAGE_RENDER_ATTACHMENT").getInt(null);
        usageBufferVertex = clsGpuBuffer.getField("USAGE_VERTEX").getInt(null);
        usageBufferUniform = clsGpuBuffer.getField("USAGE_UNIFORM").getInt(null);
        usageBufferCopyDst = clsGpuBuffer.getField("USAGE_COPY_DST").getInt(null);

        ctorVector4f = clsVector4f.getConstructor(float.class, float.class, float.class, float.class);
        identity4 = clsMatrix4f.getConstructor().newInstance();
        zero3 = clsVector3f.getConstructor(float.class, float.class, float.class).newInstance(0f, 0f, 0f);

        // Fabrique de pipelines (ex-ShaderPipelineFactory).
        mBuilderStatic = clsRenderPipeline.getMethod("builder", Array.newInstance(clsSnippet, 0).getClass());
        mWithLocation = clsBuilder.getMethod("withLocation", clsIdentifier);
        mWithVertexShader = clsBuilder.getMethod("withVertexShader", clsIdentifier);
        mWithFragmentShader = clsBuilder.getMethod("withFragmentShader", clsIdentifier);
        mWithVertexFormat = clsBuilder.getMethod("withVertexFormat", clsVertexFormat, clsVertexFormatMode);
        mWithColorTargetState = clsBuilder.getMethod("withColorTargetState", clsColorTargetState);
        mWithDepthStencilState = clsBuilder.getMethod("withDepthStencilState", clsDepthStencilState);
        mWithCull = clsBuilder.getMethod("withCull", boolean.class);
        mWithSampler = clsBuilder.getMethod("withSampler", String.class);
        mWithUniform = clsBuilder.getMethod("withUniform", String.class, clsUniformType);
        mBuild = clsBuilder.getMethod("build");
        mGetVertexFormat = clsRenderPipeline.getMethod("getVertexFormat");
        mGetVertexFormatMode = clsRenderPipeline.getMethod("getVertexFormatMode");
        mGetColorTargetState = clsRenderPipeline.getMethod("getColorTargetState");
        mGetDepthStencilState = clsRenderPipeline.getMethod("getDepthStencilState");
        mIsCull = clsRenderPipeline.getMethod("isCull");
        uniformBufferType = clsUniformType.getField("UNIFORM_BUFFER").get(null);
        // Identifier.of n'existe pas en 26.1.2 : fromNamespaceAndPath.
        mIdentifierOf = clsIdentifier.getMethod("fromNamespaceAndPath", String.class, String.class);
        guiText = clsRenderPipelines.getField("GUI_TEXT").get(null);
        if (guiText == null) throw new NoSuchFieldException("RenderPipelines.GUI_TEXT nul");

        LauncherLog.agent(3, "[Blaze3DGpuReflective261] adaptateur réflexif transitoire résolu");
    }

    private static Class<?> raw(String name) throws ClassNotFoundException {
        Class<?> c = McReflect.rawClass(name);
        if (c == null) throw new ClassNotFoundException(name);
        return c;
    }

    /** Invocation réflexive — l'exception RÉELLE du jeu remonte, jamais l'enveloppe. */
    private static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new IllegalStateException(m.getName() + " : " + c, c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(m.getName() + " inaccessible", e);
        }
    }

    @Override
    public String id() {
        return "26.1.2 (réflexif transitoire)";
    }

    // ── Appareil / encodeur / cible principale ────────────────────────────

    @Override
    public Object device() {
        return call(mGetDevice, null);
    }

    @Override
    public Object encoder(Object device) {
        return call(mCreateCommandEncoder, device);
    }

    @Override
    public Object mainColorView() {
        Object mc = McReflect.minecraftClient();
        if (mc == null) return null;
        Object target = call(mGetMainRenderTarget, mc);
        return target != null ? call(mGetColorTextureView, target) : null;
    }

    // ── Constantes d'usage ────────────────────────────────────────────────

    @Override public int usageBufferVertex() { return usageBufferVertex; }
    @Override public int usageBufferUniform() { return usageBufferUniform; }
    @Override public int usageBufferCopyDst() { return usageBufferCopyDst; }
    @Override public int usageTextureBinding() { return usageTextureBinding; }
    @Override public int usageTextureCopyDst() { return usageTextureCopyDst; }
    @Override public int usageTextureRenderAttachment() { return usageTextureRenderAttachment; }

    // ── Tampons ───────────────────────────────────────────────────────────

    @Override
    public Object createBuffer(Object device, String label, int usage, long size) {
        Supplier<String> labelSupplier = () -> label;
        return call(mCreateBuffer, device, labelSupplier, usage, size);
    }

    @Override
    public Object slice(Object buffer, long offset, long length) {
        return call(mBufferSlice, buffer, offset, length);
    }

    @Override
    public void write(Object encoder, Object slice, ByteBuffer data) {
        call(mWriteToBuffer, encoder, slice, data);
    }

    // ── Textures et images ────────────────────────────────────────────────

    @Override
    public Object createTexture(Object device, String label, int usage, int width, int height, int mipLevels) {
        Supplier<String> labelSupplier = () -> label;
        return call(mCreateTexture, device, labelSupplier, usage, formatRgba8, width, height, 1, mipLevels);
    }

    @Override
    public Object createTextureView(Object device, Object texture) {
        return call(mCreateTextureView, device, texture);
    }

    @Override
    public void closeTexture(Object texture) {
        call(mCloseTexture, texture);
    }

    @Override
    public Object newRgbaImage(int width, int height) {
        try {
            return ctorNativeImage.newInstance(nativeFormatRgba, width, height, false);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("NativeImage(" + width + "x" + height + ") : " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NativeImage(" + width + "x" + height + ")", e);
        }
    }

    @Override
    public void setPixelAbgr(Object image, int x, int y, int abgr) {
        call(mSetPixelAbgr, image, x, y, abgr);
    }

    @Override
    public void uploadImage(Object encoder, Object texture, Object image) {
        call(mWriteToTexture, encoder, texture, image);
    }

    @Override
    public void uploadImageRegion(Object encoder, Object texture, Object image, int mipLevel, int depth,
                                  int destX, int destY, int width, int height, int skipPixels, int skipRows) {
        call(mWriteToTextureRegion, encoder, texture, image, mipLevel, depth, destX, destY, width, height, skipPixels, skipRows);
    }

    @Override
    public Object linearSampler() {
        if (linearSampler == null) {
            linearSampler = call(mSamplerGetClampToEdge, call(mGetSamplerCache, null), filterLinear, true);
        }
        return linearSampler;
    }

    // ── Passe de rendu ────────────────────────────────────────────────────

    @Override
    public Object openPass(Object encoder, String label, Object colorView) {
        Supplier<String> labelSupplier = () -> label;
        return call(mCreateRenderPass, encoder, labelSupplier, colorView, OptionalInt.empty());
    }

    @Override
    public void setPipeline(Object pass, Object pipeline) {
        call(mSetPipeline, pass, pipeline);
    }

    @Override
    public void disableScissor(Object pass) {
        call(mDisableScissor, pass);
    }

    @Override
    public void bindDefaultUniforms(Object pass) {
        call(mBindDefaultUniforms, null, pass);
    }

    @Override
    public void setUniform(Object pass, String name, Object slice) {
        call(mSetUniform, pass, name, slice);
    }

    @Override
    public void bindTexture(Object pass, String name, Object textureView, Object sampler) {
        call(mBindTexture, pass, name, textureView, sampler);
    }

    @Override
    public void setVertexBuffer(Object pass, int slot, Object buffer) {
        call(mSetVertexBuffer, pass, slot, buffer);
    }

    @Override
    public void drawQuads(Object pass, int quadCount) {
        Object indices;
        try {
            indices = fieldSharedSequentialQuad.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("RenderSystem.sharedSequentialQuad inaccessible", e);
        }
        int indexCount = quadCount * 6;
        call(mSetIndexBuffer, pass, call(mIndexGetBuffer, indices, indexCount), call(mIndexType, indices));
        // drawIndexed(baseVertex, firstIndex, count, instanceCount) — ordre vérifié
        // dans les mappings (bug historique : count passé en premier, zéro géométrie).
        call(mDrawIndexed, pass, 0, 0, indexCount, 1);
    }

    @Override
    public void closePass(Object pass) {
        call(mClosePass, pass);
    }

    @Override
    public Object dynamicTransforms(float r, float g, float b, float a) {
        Object color;
        try {
            color = ctorVector4f.newInstance(r, g, b, a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Vector4f", e);
        }
        return call(mDynamicUniformsWrite, call(mGetDynamicUniforms, null), identity4, color, zero3, identity4);
    }

    // ── Pipelines maison ──────────────────────────────────────────────────

    @Override
    public Object identifier(String namespace, String path) {
        return call(mIdentifierOf, null, namespace, path);
    }

    @Override
    public Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
                                String[] samplerNames, String[] uniformBufferNames,
                                Object reference, Object vertexFormatOverride) {
        Object ref = reference != null ? reference : guiText;
        Object emptySnippets = Array.newInstance(clsSnippet, 0);
        Object builder = call(mBuilderStatic, null, emptySnippets);
        builder = call(mWithLocation, builder, identifier("yuyuframe", location));
        builder = call(mWithVertexShader, builder, vertexShaderId);
        builder = call(mWithFragmentShader, builder, fragmentShaderId);
        for (String sampler : samplerNames) {
            builder = call(mWithSampler, builder, sampler);
        }
        for (String uniformBuffer : uniformBufferNames) {
            builder = call(mWithUniform, builder, uniformBuffer, uniformBufferType);
        }
        Object format = vertexFormatOverride != null ? vertexFormatOverride : call(mGetVertexFormat, ref);
        builder = call(mWithVertexFormat, builder, format, call(mGetVertexFormatMode, ref));
        builder = call(mWithColorTargetState, builder, call(mGetColorTargetState, ref));
        // GUI_TEXT a un DepthStencilState NUL (Optional.empty() dans vanilla) : la
        // surcharge non-Optional ferait Optional.of(null) → NPE. Ne rien poser
        // donne le même résultat (build() retombe sur null).
        Object depth = call(mGetDepthStencilState, ref);
        if (depth != null) {
            builder = call(mWithDepthStencilState, builder, depth);
        }
        builder = call(mWithCull, builder, call(mIsCull, ref));
        return call(mBuild, builder);
    }

    /**
     * {@code ShaderSource} par proxy dynamique : l'interface du jeu ne peut pas
     * être implémentée par une classe ici (non compilée contre la 26.1.2). Le
     * typé {@code Blaze3DGpu261} de l'étape 3 la remplacera par une vraie classe.
     */
    @Override
    public Object shaderSource(Object vertexShaderId, String vertexGlsl, Object fragmentShaderId, String fragmentGlsl) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "get":
                    Object id = args[0];
                    if (vertexShaderId.equals(id)) return vertexGlsl;
                    if (fragmentShaderId.equals(id)) return fragmentGlsl;
                    return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return "Blaze3DGpuReflective261$ShaderSourceProxy";
            }
        };
        return Proxy.newProxyInstance(clsShaderSource.getClassLoader(), new Class<?>[]{ clsShaderSource }, handler);
    }

    @Override
    public void precompile(Object device, Object pipeline, Object shaderSource) {
        call(mPrecompilePipeline, device, pipeline, shaderSource);
    }
}
