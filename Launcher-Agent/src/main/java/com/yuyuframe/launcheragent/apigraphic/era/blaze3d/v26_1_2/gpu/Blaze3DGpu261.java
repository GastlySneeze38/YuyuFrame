package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_2.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * {@link Blaze3DGpu} pour la 26.1.2 — appels TYPÉS, aucune réflexion.
 *
 * <p>Remplace {@code Blaze3DGpuReflective261}, l'adaptateur transitoire qui
 * portait jusqu'ici TOUTE la réflexion restante du moteur Blaze3D. Avec ce
 * fichier, l'étape 3 du retrait de la réflexion est close : les deux versions
 * supportées ont une implémentation typée, et {@link Blaze3DGpus} n'a plus de
 * repli.
 *
 * <p>Compilé dans l'unité PRINCIPALE, contre {@code src/stubs/v26_1} — pas
 * dans une unité séparée comme {@code Blaze3DGpu1211}. La raison de cette
 * asymétrie tient en un mot : la 26.1.2 n'est pas obfusquée. Aucun type à
 * nommer en Yarn, donc aucune traduction au chargement, donc aucune contrainte
 * de paquet ({@code YarnNamedRemapper} ne s'applique pas ici). Cette classe
 * n'est chargée que sur 26.1.2, où ses types existent : sur une autre version
 * le fournisseur ne la construit jamais.
 *
 * <p>Divergences réelles avec la 1.21.11, toutes vérifiées sur le jar client :
 * <ul>
 *   <li>{@code GpuDevice}, {@code CommandEncoder} et {@code RenderPass} sont
 *       des CLASSES ici, des interfaces là-bas ;</li>
 *   <li>l'état couleur/profondeur est un objet unique recopiable
 *       ({@code ColorTargetState}/{@code DepthStencilState}), là où la 1.21.11
 *       l'éclate en réglages séparés ;</li>
 *   <li>{@code DynamicUniforms.writeTransform} ici, {@code write} là-bas ;</li>
 *   <li>{@code NativeImage.setPixelABGR} ici, {@code setColor} là-bas ;</li>
 *   <li>{@code Identifier.fromNamespaceAndPath} ici, {@code Identifier.of}
 *       là-bas.</li>
 * </ul>
 */
final class Blaze3DGpu261 implements Blaze3DGpu {

    /** Constantes LUES par {@code DynamicUniforms.writeTransform} — partagées, jamais modifiées. */
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f ZERO = new Vector3f(0f, 0f, 0f);

    @Override
    public String id() {
        return "26.1.2";
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        RenderTarget target = mc.getMainRenderTarget();
        return target != null ? target.getColorTextureView() : null;
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
        ((NativeImage) image).setPixelABGR(x, y, abgr);
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
        // getClampToEdge(FilterMode, true) : plage de LOD COMPLÈTE — la
        // surcharge à un argument fige le LOD à 0 et rend les mips inutiles
        // (bug historique du texte flou).
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
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
        // getSequentialBuffer(QUADS) rend le tampon que l'adaptateur réflexif
        // lisait par le champ PRIVÉ RenderSystem.sharedSequentialQuad — le seul
        // accès qu'un appel typé ne pouvait pas reproduire, et la raison pour
        // laquelle cet accesseur public a été cherché puis vérifié sur le jar.
        RenderSystem.AutoStorageIndexBuffer indices =
            RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        int indexCount = quadCount * 6;
        RenderPass p = (RenderPass) pass;
        p.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
        // drawIndexed(baseVertex, firstIndex, count, instanceCount) — ordre
        // vérifié (bug historique : count passé en premier, zéro géométrie).
        p.drawIndexed(0, 0, indexCount, 1);
    }

    @Override
    public void closePass(Object pass) {
        ((RenderPass) pass).close();
    }

    @Override
    public Object dynamicTransforms(float r, float g, float b, float a) {
        return RenderSystem.getDynamicUniforms()
            .writeTransform(IDENTITY, new Vector4f(r, g, b, a), ZERO, IDENTITY);
    }

    // ── Pipelines maison ──────────────────────────────────────────────────

    @Override
    public Object identifier(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    @Override
    public Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
                                String[] samplerNames, String[] uniformBufferNames,
                                Object reference, Object vertexFormatOverride) {
        RenderPipeline ref = reference != null ? (RenderPipeline) reference : RenderPipelines.GUI_TEXT;
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("yuyuframe", location))
            .withVertexShader((Identifier) vertexShaderId)
            .withFragmentShader((Identifier) fragmentShaderId);
        for (String sampler : samplerNames) {
            builder = builder.withSampler(sampler);
        }
        for (String uniformBuffer : uniformBufferNames) {
            builder = builder.withUniform(uniformBuffer, UniformType.UNIFORM_BUFFER);
        }
        VertexFormat format = vertexFormatOverride != null
            ? (VertexFormat) vertexFormatOverride
            : ref.getVertexFormat();
        builder = builder.withVertexFormat(format, ref.getVertexFormatMode());

        ColorTargetState color = ref.getColorTargetState();
        builder = builder.withColorTargetState(color);
        // GUI_TEXT a un DepthStencilState NUL (Optional.empty() côté vanilla) :
        // la surcharge non-Optional ferait Optional.of(null) et lèverait un NPE.
        // Ne rien poser donne le même résultat (build() retombe sur null).
        DepthStencilState depth = ref.getDepthStencilState();
        if (depth != null) {
            builder = builder.withDepthStencilState(depth);
        }
        return builder.withCull(ref.isCull()).build();
    }

    @Override
    public Object shaderSource(Object vertexShaderId, String vertexGlsl, Object fragmentShaderId, String fragmentGlsl) {
        return new HomeShaderSource((Identifier) vertexShaderId, vertexGlsl,
            (Identifier) fragmentShaderId, fragmentGlsl);
    }

    @Override
    public void precompile(Object device, Object pipeline, Object shaderSource) {
        ((GpuDevice) device).precompilePipeline((RenderPipeline) pipeline, (ShaderSource) shaderSource);
    }

    /**
     * Source GLSL de nos pipelines — vraie classe, là où l'adaptateur réflexif
     * devait fabriquer un {@code java.lang.reflect.Proxy} faute de pouvoir
     * implémenter une interface du jeu.
     */
    private static final class HomeShaderSource implements ShaderSource {
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
