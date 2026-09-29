package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_3.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderSource.CachedIncludeSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.Blaze3DGpu;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.GlobalUiRenderBridge263;
import com.yuyuframe.launcheragent.apimixin.v26_3.render.DefaultVertexFormatAccessor263;
import com.yuyuframe.launcheragent.apimixin.v26_3.render.RenderSystemAccessor263;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Blaze3DGpu} pour la 26.3 — copie de la version 26.2 (ci-dessous),
 * portée sur renderpearl, le Blaze3D renommé de la 26.3 (2026-09-29). Écarts
 * relevés par javap sur le jar client 26.3 :
 * <ul>
 *   <li>paquets {@code com.mojang.renderpearl.api.*} ; {@code GpuDevice},
 *       {@code CommandEncoder}, {@code RenderPass}, {@code GpuBuffer},
 *       {@code GpuTexture}(View), {@code GpuSampler} deviennent des
 *       interfaces ;</li>
 *   <li>{@code RenderPass.setPipeline} prend un pipeline COMPILÉ, et
 *       {@code precompilePipeline} n'existe plus : on compile par
 *       {@code GpuDevice.compilePipeline} et on garde le résultat — voir
 *       {@link #precompile} ;</li>
 *   <li>{@code bindTexture} devient {@code setUniform(nom, vue, sampler)},
 *       les samplers se déclarent en {@code COMBINED_IMAGE_SAMPLER} ;</li>
 *   <li>{@code ShaderSource} : {@code getShader}, {@code getInclude},
 *       {@code close} ; nos GLSL passent par {@link Glsl263} ;</li>
 *   <li>{@code DynamicUniforms} devient {@code DynamicGpuData} (même
 *       {@code writeTransform}) ; {@code getColorTargetState()} devient la
 *       liste {@code getColorTargetStates()}.</li>
 * </ul>
 *
 * <p>Historique de la version 26.2 dont ce fichier est parti :
 *
 * <p>{@link Blaze3DGpu} pour la 26.2 — portage de {@code Blaze3DGpu261} sur le
 * Blaze3D refondu de la 26.2 (2026-09-29). Mêmes opérations pour le moteur
 * partagé ; les écarts d'API, tous relevés par javap sur le jar client 26.2 :
 * <ul>
 *   <li>cible principale : {@code GameRenderer.mainRenderTarget} (accessors),
 *       {@code Minecraft.getMainRenderTarget()} n'existe plus ;</li>
 *   <li>{@code GpuFormat.RGBA8_UNORM} au lieu de {@code TextureFormat.RGBA8} ;</li>
 *   <li>{@code writeToTexture} n'envoie plus que l'image ENTIÈRE à une
 *       position (plus de largeur/hauteur ni de sauts de lignes) ;</li>
 *   <li>couleur d'effacement d'une passe : {@code Optional<Vector4fc>} ;</li>
 *   <li>{@code setVertexBuffer} prend une slice, {@code drawIndexed} cinq
 *       entiers dans l'ordre Vulkan, tampon séquentiel par
 *       {@code PrimitiveTopology} ;</li>
 *   <li>pipelines : samplers et uniformes dans un {@code BindGroupLayout},
 *       format par {@code withVertexBinding} + {@code withPrimitiveTopology}.</li>
 * </ul>
 *
 * <p>Historique de la version 26.1.2 dont ce fichier est parti :
 *
 * <p>{@link Blaze3DGpu} pour la 26.1.2 — appels TYPÉS, aucune réflexion.
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
final class Blaze3DGpu263 implements Blaze3DGpu {

    /** Constantes LUES par {@code DynamicUniforms.writeTransform} — partagées, jamais modifiées. */
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f ZERO = new Vector3f(0f, 0f, 0f);

    @Override
    public String id() {
        return "26.3";
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
        // 26.2 : la cible principale vit dans GameRenderer.mainRenderTarget,
        // lue par accessors — voir GlobalUiRenderBridge263.getMainFramebuffer.
        Object mc = GlobalUiRenderBridge263.getMcInstance();
        if (mc == null) return null;
        Object target = GlobalUiRenderBridge263.getMainFramebuffer(mc);
        return target instanceof RenderTarget ? ((RenderTarget) target).getColorTextureView() : null;
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
        return ((GpuDevice) device).createTexture(() -> label, usage, GpuFormat.RGBA8_UNORM, width, height, 1, mipLevels);
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

    /**
     * 26.2 : {@code writeToTexture(texture, image, mip, depth, x, y)} n'envoie
     * que l'image ENTIÈRE à la position donnée — la variante qui recadrait
     * (largeur, hauteur, sauts de pixels et de lignes) a disparu. Les deux
     * appelants du moteur (atlas d'icônes, mips du texte) envoient justement
     * une image entière, sans saut ; tout autre usage est refusé bruyamment
     * plutôt que d'écrire une région fausse en silence.
     */
    @Override
    public void uploadImageRegion(Object encoder, Object texture, Object image, int mipLevel, int depth,
                                  int destX, int destY, int width, int height, int skipPixels, int skipRows) {
        NativeImage source = (NativeImage) image;
        if (skipPixels != 0 || skipRows != 0 || width != source.getWidth() || height != source.getHeight()) {
            throw new UnsupportedOperationException("uploadImageRegion 26.2 : seule l'image entière est envoyable"
                + " (demandé " + width + "x" + height + " sauts " + skipPixels + "/" + skipRows
                + ", image " + source.getWidth() + "x" + source.getHeight() + ")");
        }
        ((CommandEncoder) encoder).writeToTexture((GpuTexture) texture, source, mipLevel, depth, destX, destY);
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
        // 26.2 : pas d'effacement = Optional.empty() (ex-OptionalInt.empty()).
        return ((CommandEncoder) encoder).createRenderPass(() -> label, (GpuTextureView) colorView, Optional.empty());
    }

    @Override
    public void setPipeline(Object pass, Object pipeline) {
        // 26.3 : la passe veut le pipeline COMPILÉ. Les nôtres sortent de
        // precompile (toujours appelé juste avant) ; un pipeline vanilla passe
        // par le cache du jeu.
        RenderPipeline p = (RenderPipeline) pipeline;
        CompiledRenderPipeline compiled = COMPILED.get(p);
        if (compiled == null || compiled.isClosed()) {
            compiled = RenderSystem.getCompiledPipeline(p);
        }
        ((RenderPass) pass).setPipeline(compiled);
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
        // 26.3 : bindTexture devient une surcharge de setUniform.
        ((RenderPass) pass).setUniform(name, (GpuTextureView) textureView, (GpuSampler) sampler);
    }

    @Override
    public void setVertexBuffer(Object pass, int slot, Object buffer) {
        // 26.2 : le tampon se passe en slice — le tampon entier, comme avant.
        ((RenderPass) pass).setVertexBuffer(slot, ((GpuBuffer) buffer).slice());
    }

    @Override
    public void drawQuads(Object pass, int quadCount) {
        // getSequentialBuffer(QUADS) rend le tampon d'indices séquentiel
        // partagé du jeu (quads → triangles). 26.2 : QUADS est un
        // PrimitiveTopology (ex-VertexFormat.Mode).
        RenderSystem.AutoStorageIndexBuffer indices =
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int indexCount = quadCount * 6;
        RenderPass p = (RenderPass) pass;
        p.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
        // 26.2 : drawIndexed(indexCount, instanceCount, firstIndex, baseVertex,
        // firstInstance) — ordre Vulkan, relevé sur l'appel de GuiRenderer
        // (count, 1, firstIndex, baseVertex, 0). En 26.1.2 c'était
        // (baseVertex, firstIndex, count, instanceCount) : un ordre faux ne
        // lève rien, il ne dessine simplement aucune géométrie.
        p.drawIndexed(indexCount, 1, 0, 0, 0);
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
        // 26.2 : samplers et blocs d'uniformes vont dans un BindGroupLayout —
        // un seul groupe par pipeline suffit : BindGroupLayout.ensureCompatible
        // (seul contrôle, fait par GlProgram) n'exige que l'unicité des noms
        // entre groupes, jamais un découpage précis (vérifié par javap).
        BindGroupLayout.Builder bindings = BindGroupLayout.builder();
        // 26.3 : withSampler a disparu, un sampler est un uniforme COMBINED_IMAGE_SAMPLER.
        for (String sampler : samplerNames) {
            bindings = bindings.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        for (String uniformBuffer : uniformBufferNames) {
            bindings = bindings.withUniform(uniformBuffer, UniformType.UNIFORM_BUFFER);
        }
        builder = builder.withBindGroupLayout(bindings.build());
        // 26.2 : withVertexFormat(format, mode) devient withVertexBinding(0,
        // format) + withPrimitiveTopology(topologie de la référence).
        //
        // Sans référence ni format fournis (tout le chemin « en file » :
        // rects, texte, dégradés, flou…), le format N'EST PLUS hérité de
        // GUI_TEXT : en 26.2 il y est passé de POSITION_COLOR_TEX_LIGHTMAP
        // (28 octets) à POSITION_TEX_COLOR (24 octets, autre ordre), alors que
        // le moteur écrit toujours ses sommets en Position+Color+UV0+UV2
        // (Blaze3DCore.putVertexPCTL). Hériter donnait une géométrie mal lue,
        // invisible, sans aucune erreur — interface vide au test du
        // 2026-09-29. On pose donc explicitement le format que le moteur écrit.
        VertexFormat format;
        if (vertexFormatOverride != null) {
            format = (VertexFormat) vertexFormatOverride;
        } else if (reference == null) {
            format = DefaultVertexFormatAccessor263.la$positionColorTexLightmap();
        } else {
            format = ref.getVertexFormatBinding(0);
        }
        builder = builder.withVertexBinding(0, format)
            .withPrimitiveTopology(ref.getPrimitiveTopology());

        // 26.3 : une liste d'états par cible couleur — nos passes n'écrivent
        // qu'une cible, celle d'indice 0 (withColorTargetState(état) la pose).
        List<ColorTargetState> colors = ref.getColorTargetStates();
        if (!colors.isEmpty()) {
            builder = builder.withColorTargetState(colors.get(0));
        }
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

    /**
     * Nos pipelines compilés, par pipeline — 26.3. Identité, comme le
     * {@code Reference2ReferenceOpenHashMap} de {@code PipelineCache}.
     * Rendu uniquement (pas de concurrence).
     */
    private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();

    /**
     * 26.3 : {@code precompilePipeline} n'existe plus. On compile comme
     * {@code PipelineCache.get} (javap) — {@code compilePipeline(...).join()
     * .finishCompile()} —, mais avec NOTRE source et un exécuteur direct
     * (la compilation se fait sur place, là où le jeu passe par son pool
     * d'arrière-plan pour aussitôt l'attendre).
     *
     * <p>Appelé avant chaque dessin : on ne compile qu'une fois, sauf si le
     * jeu a fermé le résultat entre-temps. C'est ce qui arrive au rechargement
     * des ressources : {@code ShaderManager.apply} crée un nouveau cache et
     * ferme l'ancien — nos pipelines inscrits dedans compris.
     *
     * <p>Le pipeline compilé est aussi inscrit dans le cache ACTIF du jeu
     * (courant, sinon secours — l'ordre de {@code getCompiledPipelineNullable}) :
     * les pipelines de l'interface vanilla ({@code vanillagui/pipeline}) sont
     * dessinés par le {@code GuiRenderer}, qui les demande à ce cache. Sans
     * cette inscription, il les compilerait avec la source du jeu, où nos
     * shaders n'existent pas. Un seul cache, jamais les deux : chacun ferme
     * ses entrées à sa fermeture, un pipeline partagé serait fermé deux fois.
     */
    @Override
    public void precompile(Object device, Object pipeline, Object shaderSource) {
        RenderPipeline p = (RenderPipeline) pipeline;
        CompiledRenderPipeline compiled = COMPILED.get(p);
        if (compiled == null || compiled.isClosed()) {
            compiled = ((GpuDevice) device)
                .compilePipeline(p, (ShaderSource) shaderSource, Runnable::run)
                .join()
                .finishCompile();
            COMPILED.put(p, compiled);
        }
        PipelineCache cache = RenderSystemAccessor263.la$currentPipelineCache();
        if (cache == null) cache = RenderSystemAccessor263.la$fallbackPipelineCache();
        if (cache != null) cache.insert(p, compiled);
    }

    /**
     * Source GLSL de nos pipelines — vraie classe, là où l'adaptateur réflexif
     * devait fabriquer un {@code java.lang.reflect.Proxy} faute de pouvoir
     * implémenter une interface du jeu. 26.3 : le texte passe par
     * {@link Glsl263} (emplacements explicites, ordre de DynamicTransforms).
     */
    private static final class HomeShaderSource implements ShaderSource {
        private final Identifier vertexId, fragmentId;
        private final String vertexGlsl, fragmentGlsl;

        HomeShaderSource(Identifier vertexId, String vertexGlsl, Identifier fragmentId, String fragmentGlsl) {
            String[] adapted = Glsl263.adapt(vertexGlsl, fragmentGlsl);
            this.vertexId = vertexId;
            this.vertexGlsl = adapted[0];
            this.fragmentId = fragmentId;
            this.fragmentGlsl = adapted[1];
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            if (vertexId.equals(id)) return vertexGlsl;
            if (fragmentId.equals(id)) return fragmentGlsl;
            return null;
        }

        /** Nos shaders n'importent rien ({@code #moj_import}) : toute demande est une erreur. */
        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return CachedIncludeSource.createError("LauncherAgent : aucun include pour " + id);
        }

        /** Rien à libérer : du texte en mémoire. */
        @Override
        public void close() {
        }
    }
}
