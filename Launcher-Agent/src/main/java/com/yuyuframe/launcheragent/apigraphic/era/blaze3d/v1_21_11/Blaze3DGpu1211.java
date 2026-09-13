package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGpu;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.ShaderSourceGetter;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link Blaze3DGpu} pour la 1.21.11 — appels TYPÉS, aucune réflexion.
 *
 * <p>Compilé contre {@code src/stubs/v1_21_11} : classes {@code com.mojang.blaze3d.*}
 * sous leur vrai nom, types obfusqués sous leur nom Yarn ({@code GpuSampler},
 * {@code UniformType}, {@code Identifier}…). Les noms Yarn sont traduits au
 * chargement vers ceux du loader actif par {@code YarnNamedRemapper} — c'est
 * pourquoi cette classe DOIT rester dans ce paquet (le seul qu'il traduit).
 *
 * <p>Équivalences avec le chemin réflexif de {@code Blaze3DCore} (26.1.2) :
 * mêmes appels, mêmes paramètres ; seule la construction de pipeline diffère,
 * l'API 1.21.11 n'ayant ni {@code ColorTargetState} ni {@code DepthStencilState}
 * (voir {@link #buildPipeline}).
 */
@YarnNamed
public final class Blaze3DGpu1211 implements Blaze3DGpu {

    /** Constantes LUES par {@code DynamicUniforms.write} — partagées, jamais modifiées. */
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f ZERO = new Vector3f(0f, 0f, 0f);

    @Override
    public String id() {
        return "1.21.11";
    }

    // ── Appareil / encodeur / cible principale ────────────────────────────

    @Override
    public Object device() {
        return RenderSystem.getDevice();
    }

    @Override
    public Object encoder(Object device) {
        return ((GpuDevice) device).createCommandEncoder();
    }

    @Override
    public Object mainColorView() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return null;
        Framebuffer fb = mc.getFramebuffer();
        return fb != null ? fb.getColorAttachmentView() : null;
    }

    // ── Constantes d'usage ────────────────────────────────────────────────

    @Override public int usageBufferVertex() { return GpuBuffer.USAGE_VERTEX; }
    @Override public int usageBufferUniform() { return GpuBuffer.USAGE_UNIFORM; }
    @Override public int usageBufferCopyDst() { return GpuBuffer.USAGE_COPY_DST; }
    @Override public int usageTextureBinding() { return GpuTexture.USAGE_TEXTURE_BINDING; }
    @Override public int usageTextureCopyDst() { return GpuTexture.USAGE_COPY_DST; }
    @Override public int usageTextureRenderAttachment() { return GpuTexture.USAGE_RENDER_ATTACHMENT; }

    // ── Tampons ───────────────────────────────────────────────────────────

    @Override
    public Object createBuffer(Object device, String label, int usage, long size) {
        return ((GpuDevice) device).createBuffer(() -> label, usage, size);
    }

    @Override
    public Object slice(Object buffer, long offset, long length) {
        return ((GpuBuffer) buffer).slice(offset, length);
    }

    @Override
    public void write(Object encoder, Object slice, ByteBuffer data) {
        ((CommandEncoder) encoder).writeToBuffer((GpuBufferSlice) slice, data);
    }

    // ── Textures et images ────────────────────────────────────────────────

    @Override
    public Object createTexture(Object device, String label, int usage, int width, int height, int mipLevels) {
        return ((GpuDevice) device).createTexture(() -> label, usage, TextureFormat.RGBA8, width, height, 1, mipLevels);
    }

    @Override
    public Object createTextureView(Object device, Object texture) {
        return ((GpuDevice) device).createTextureView((GpuTexture) texture);
    }

    @Override
    public void closeTexture(Object texture) {
        ((GpuTexture) texture).close();
    }

    @Override
    public Object newRgbaImage(int width, int height) {
        return new NativeImage(NativeImage.Format.RGBA, width, height, false);
    }

    @Override
    public void setPixelAbgr(Object image, int x, int y, int abgr) {
        // NativeImage.setColor(x, y, ABGR) — même méthode que le chemin
        // réflexif d'origine (Blaze3DCore.mNativeImageSetColor, branche Yarn).
        ((NativeImage) image).setColor(x, y, abgr);
    }

    @Override
    public void uploadImage(Object encoder, Object texture, Object image) {
        ((CommandEncoder) encoder).writeToTexture((GpuTexture) texture, (NativeImage) image);
    }

    @Override
    public void uploadImageRegion(Object encoder, Object texture, Object image, int mipLevel, int depth,
                                  int destX, int destY, int width, int height, int skipPixels, int skipRows) {
        ((CommandEncoder) encoder).writeToTexture((GpuTexture) texture, (NativeImage) image,
            mipLevel, depth, destX, destY, width, height, skipPixels, skipRows);
    }

    @Override
    public Object linearSampler() {
        // get(FilterMode, true) : plage de LOD COMPLÈTE — la surcharge à un
        // argument fige le LOD à 0 et rend les mips inutiles (bug historique,
        // voir Blaze3DCore.resolve).
        return RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true);
    }

    // ── Passe de rendu ────────────────────────────────────────────────────

    @Override
    public Object openPass(Object encoder, String label, Object colorView) {
        return ((CommandEncoder) encoder).createRenderPass(() -> label, (GpuTextureView) colorView, OptionalInt.empty());
    }

    @Override
    public void setPipeline(Object pass, Object pipeline) {
        ((RenderPass) pass).setPipeline((RenderPipeline) pipeline);
    }

    @Override
    public void disableScissor(Object pass) {
        ((RenderPass) pass).disableScissor();
    }

    @Override
    public void bindDefaultUniforms(Object pass) {
        RenderSystem.bindDefaultUniforms((RenderPass) pass);
    }

    @Override
    public void setUniform(Object pass, String name, Object slice) {
        ((RenderPass) pass).setUniform(name, (GpuBufferSlice) slice);
    }

    @Override
    public void bindTexture(Object pass, String name, Object textureView, Object sampler) {
        ((RenderPass) pass).bindTexture(name, (GpuTextureView) textureView, (GpuSampler) sampler);
    }

    @Override
    public void setVertexBuffer(Object pass, int slot, Object buffer) {
        ((RenderPass) pass).setVertexBuffer(slot, (GpuBuffer) buffer);
    }

    @Override
    public void drawQuads(Object pass, int quadCount) {
        // getSequentialBuffer(QUADS) = RenderSystem.sharedSequentialQuad, le
        // tampon que le chemin réflexif lisait par son champ privé.
        RenderSystem.ShapeIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
        int indexCount = quadCount * 6;
        RenderPass p = (RenderPass) pass;
        p.setIndexBuffer(indices.getIndexBuffer(indexCount), indices.getIndexType());
        p.drawIndexed(0, 0, indexCount, 1);
    }

    @Override
    public void closePass(Object pass) {
        ((RenderPass) pass).close();
    }

    @Override
    public Object dynamicTransforms(float r, float g, float b, float a) {
        return RenderSystem.getDynamicUniforms().write(IDENTITY, new Vector4f(r, g, b, a), ZERO, IDENTITY);
    }

    // ── Pipelines maison ──────────────────────────────────────────────────

    @Override
    public Object identifier(String namespace, String path) {
        return Identifier.of(namespace, path);
    }

    /**
     * En 1.21.11, l'état couleur/profondeur n'est pas un objet unique à
     * recopier ({@code ColorTargetState}/{@code DepthStencilState} en 26.1.2) :
     * il est éclaté en réglages, qu'on recopie un par un depuis la référence —
     * même résultat que la copie en bloc de la 26.1.2.
     */
    @Override
    public Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
                                String[] samplerNames, String[] uniformBufferNames,
                                Object reference, Object vertexFormatOverride) {
        RenderPipeline ref = reference != null ? (RenderPipeline) reference : RenderPipelines.GUI_TEXT;
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.of("yuyuframe", location))
            .withVertexShader((Identifier) vertexShaderId)
            .withFragmentShader((Identifier) fragmentShaderId);
        for (String sampler : samplerNames) {
            builder = builder.withSampler(sampler);
        }
        for (String uniformBuffer : uniformBufferNames) {
            builder = builder.withUniform(uniformBuffer, UniformType.UNIFORM_BUFFER);
        }
        VertexFormat format = vertexFormatOverride != null ? (VertexFormat) vertexFormatOverride : ref.getVertexFormat();
        builder = builder.withVertexFormat(format, ref.getVertexFormatMode());

        Optional<BlendFunction> blend = ref.getBlendFunction();
        builder = blend.isPresent() ? builder.withBlend(blend.get()) : builder.withoutBlend();
        builder = builder
            .withColorWrite(ref.isWriteColor(), ref.isWriteAlpha())
            .withColorLogic(ref.getColorLogic())
            .withDepthTestFunction(ref.getDepthTestFunction())
            .withDepthWrite(ref.isWriteDepth())
            .withDepthBias(ref.getDepthBiasScaleFactor(), ref.getDepthBiasConstant())
            .withPolygonMode(ref.getPolygonMode())
            .withCull(ref.isCull());
        return builder.build();
    }

    @Override
    public Object shaderSource(Object vertexShaderId, String vertexGlsl, Object fragmentShaderId, String fragmentGlsl) {
        return new HomeShaderSource((Identifier) vertexShaderId, vertexGlsl, (Identifier) fragmentShaderId, fragmentGlsl);
    }

    @Override
    public void precompile(Object device, Object pipeline, Object shaderSource) {
        ((GpuDevice) device).precompilePipeline((RenderPipeline) pipeline, (ShaderSourceGetter) shaderSource);
    }

    /**
     * Source GLSL de nos pipelines. Classe NOMMÉE, pas une lambda : elle
     * implémente une interface du jeu, et {@code YarnNamedRemapper} ne traduit
     * pas la méthode fonctionnelle d'une lambda (voir sa javadoc). Remplace le
     * proxy dynamique ({@code java.lang.reflect.Proxy}) de ShaderPipelineFactory.
     */
    @YarnNamed // classe imbriquée = fichier .class distinct, n'hérite pas du marqueur
    private static final class HomeShaderSource implements ShaderSourceGetter {
        private final Identifier vertexId, fragmentId;
        private final String vertexGlsl, fragmentGlsl;

        HomeShaderSource(Identifier vertexId, String vertexGlsl, Identifier fragmentId, String fragmentGlsl) {
            this.vertexId = vertexId;
            this.vertexGlsl = vertexGlsl;
            this.fragmentId = fragmentId;
            this.fragmentGlsl = fragmentGlsl;
        }

        @Override
        public String get(Identifier id, ShaderType type) {
            if (vertexId.equals(id)) return vertexGlsl;
            if (fragmentId.equals(id)) return fragmentGlsl;
            return null;
        }
    }
}
