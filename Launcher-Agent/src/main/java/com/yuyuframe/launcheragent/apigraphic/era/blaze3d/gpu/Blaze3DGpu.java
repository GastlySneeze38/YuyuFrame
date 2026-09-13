package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu;

import java.nio.ByteBuffer;

/**
 * Les opérations GPU dont l'ère Blaze3D du moteur a besoin — et rien d'autre.
 *
 * <h2>Pourquoi cette interface</h2>
 *
 * Le moteur ne doit plus faire de réflexion. Or l'API Blaze3D diffère d'une
 * version à l'autre (la 1.21.11 n'a ni {@code ColorTargetState} ni {@code
 * DepthStencilState}, ses types de support sont obfusqués…), et ses classes
 * portent les MÊMES noms d'une version à l'autre : impossible de compiler un
 * seul code typé pour toutes. Chaque version fournit donc son implémentation
 * TYPÉE, compilée dans sa propre unité (voir {@code build.bat}), et le moteur
 * ne parle qu'à cette interface.
 *
 * <h2>Pourquoi des {@code Object}</h2>
 *
 * Le moteur est compilé une seule fois, contre aucune version : il ne peut pas
 * nommer {@code RenderPass} ou {@code GpuBuffer}. Il manipule donc des poignées
 * opaques, qu'il ne fait que repasser à l'implémentation — jamais d'invocation
 * réflexive, les appels réels sont typés de l'autre côté.
 *
 * <h2>Contrat</h2>
 * <ul>
 *   <li>Toutes les méthodes sont appelées sur le thread de rendu.</li>
 *   <li>Les poignées ne sont valables que pour l'implémentation qui les a
 *       produites (jamais mélangées entre versions : une seule tourne).</li>
 *   <li>Les exceptions remontent telles quelles à l'appelant, qui les
 *       journalise avec son étape courante.</li>
 * </ul>
 *
 * <p>Obtenue par {@link Blaze3DGpus#active()}.
 */
public interface Blaze3DGpu {

    /** Identifiant lisible pour les logs (ex. {@code "1.21.11"}). */
    String id();

    // ── Appareil / encodeur / cible principale ────────────────────────────

    Object device();

    Object encoder(Object device);

    /** Vue couleur du framebuffer PRINCIPAL du jeu, ou {@code null} s'il n'est pas encore prêt. */
    Object mainColorView();

    // ── Constantes d'usage ────────────────────────────────────────────────

    int usageBufferVertex();

    int usageBufferUniform();

    int usageBufferCopyDst();

    int usageTextureBinding();

    int usageTextureCopyDst();

    int usageTextureRenderAttachment();

    // ── Tampons ───────────────────────────────────────────────────────────

    Object createBuffer(Object device, String label, int usage, long size);

    Object slice(Object buffer, long offset, long length);

    void write(Object encoder, Object slice, ByteBuffer data);

    // ── Textures et images ────────────────────────────────────────────────

    /** Texture RGBA8. */
    Object createTexture(Object device, String label, int usage, int width, int height, int mipLevels);

    Object createTextureView(Object device, Object texture);

    void closeTexture(Object texture);

    /** Image CPU RGBA ({@code NativeImage}), à remplir puis envoyer par {@link #uploadImage}. */
    Object newRgbaImage(int width, int height);

    /** Pixel au format ABGR packé ({@code (a<<24)|(b<<16)|(g<<8)|r}). */
    void setPixelAbgr(Object image, int x, int y, int abgr);

    void uploadImage(Object encoder, Object texture, Object image);

    /** Variante détaillée : niveau de mip explicite (voir {@code CommandEncoder.writeToTexture}). */
    void uploadImageRegion(Object encoder, Object texture, Object image, int mipLevel, int depth,
                           int destX, int destY, int width, int height, int skipPixels, int skipRows);

    /** Échantillonneur linéaire, plage de LOD complète (mips utilisés). */
    Object linearSampler();

    // ── Passe de rendu ────────────────────────────────────────────────────

    Object openPass(Object encoder, String label, Object colorView);

    void setPipeline(Object pass, Object pipeline);

    void disableScissor(Object pass);

    void bindDefaultUniforms(Object pass);

    void setUniform(Object pass, String name, Object slice);

    void bindTexture(Object pass, String name, Object textureView, Object sampler);

    void setVertexBuffer(Object pass, int slot, Object buffer);

    /** {@code quadCount} quads via le tampon d'indices séquentiel partagé du jeu (QUADS → triangles). */
    void drawQuads(Object pass, int quadCount);

    void closePass(Object pass);

    /** {@code DynamicTransforms} : modelView identité, {@code ColorModulator} = (r,g,b,a), offset nul. */
    Object dynamicTransforms(float r, float g, float b, float a);

    // ── Pipelines maison ──────────────────────────────────────────────────

    Object identifier(String namespace, String path);

    /**
     * Pipeline maison : GLSL désigné par {@code vertexShaderId}/{@code
     * fragmentShaderId}, format de sommet et état couleur/profondeur/cull
     * COPIÉS depuis {@code reference} ({@code null} = le pipeline de texte de la GUI vanilla).
     *
     * @param vertexFormatOverride format à utiliser à la place de celui de la
     *        référence, {@code null} pour le copier.
     */
    Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
                         String[] samplerNames, String[] uniformBufferNames,
                         Object reference, Object vertexFormatOverride);

    /** Source GLSL répondant {@code vertexGlsl}/{@code fragmentGlsl} pour ces deux identifiants. */
    Object shaderSource(Object vertexShaderId, String vertexGlsl, Object fragmentShaderId, String fragmentGlsl);

    /** À appeler avant chaque {@code setPipeline} (no-op si déjà en cache ; nécessaire après F3+T). */
    void precompile(Object device, Object pipeline, Object shaderSource);
}
