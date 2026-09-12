package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

/**
 * Façade de construction de {@code RenderPipeline} maison (roadmap Phase 5,
 * "pipeline shader maison") — conservée pour ses appelants (état de GUI
 * vanilla {@code Blaze3DGui*}, {@code UiSolidPipelinePoc}).
 *
 * <p>Ne fait PLUS aucune réflexion : chaque appel est délégué à l'implémentation
 * {@link Blaze3DGpu} de la version en cours ({@link Blaze3DGpus#active()}).
 * L'ancien mécanisme — {@code GpuDevice.precompilePipeline(RenderPipeline,
 * ShaderSource)} avec un {@code ShaderSource} répondant notre GLSL embarqué,
 * format de sommet et état GPU copiés d'un pipeline de référence (technique
 * d'UniversalCraft, {@code URenderPipeline.kt}) — vit désormais dans les
 * implémentations typées {@code Blaze3DGpu1211} et {@code Blaze3DGpu261}.
 */
public final class ShaderPipelineFactory {
    private ShaderPipelineFactory() {}

    /**
     * {@code true} sur l'ère Blaze3D (1.21.11 / 26.x).
     *
     * <p>L'ère est REÇUE — voir {@code RenderEra}.
     */
    public static boolean isAvailable() {
        return com.yuyuframe.launcheragent.apigraphic.backend.RenderEra.active()
            == com.yuyuframe.launcheragent.apigraphic.backend.RenderEra.BLAZE3D;
    }

    private static Blaze3DGpu gpu() {
        Blaze3DGpu gpu = Blaze3DGpus.active();
        if (gpu == null) {
            throw new IllegalStateException("ShaderPipelineFactory indisponible : aucune implémentation Blaze3DGpu pour cette version");
        }
        return gpu;
    }

    /** Équivalent de {@code Identifier.of(namespace, path)}. */
    public static Object identifier(String namespace, String path) {
        return gpu().identifier(namespace, path);
    }

    /** Équivalent de {@code RenderSystem.getDevice()}. */
    public static Object device() {
        return gpu().device();
    }

    /** Pipeline maison, format de sommet et état GPU copiés de {@code GUI_TEXT}, sans sampler ni bloc d'uniformes. */
    public static Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId) {
        return buildPipeline(location, vertexShaderId, fragmentShaderId, new String[0], new String[0]);
    }

    /**
     * Comme ci-dessus, avec des samplers ({@code "Sampler0"}…) et des blocs
     * {@code UNIFORM_BUFFER} ({@code "DynamicTransforms"}…) déclarés
     * EXPLICITEMENT — jamais copiés de {@code GUI_TEXT}, qui déclare aussi un
     * bloc {@code Fog} que notre GLSL n'implémente pas.
     */
    public static Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
            String[] samplerNames, String[] uniformBufferNames) {
        return buildPipeline(location, vertexShaderId, fragmentShaderId, samplerNames, uniformBufferNames, null, null);
    }

    /**
     * @param referencePipeline pipeline dont copier format de sommet, état
     *        couleur/profondeur et cull — {@code null} pour {@code GUI_TEXT}.
     *        À fournir depuis un ACCESSOR, jamais par réflexion. Piège connu :
     *        un pipeline confié à l'état de GUI vanilla doit avoir le format
     *        des sommets qu'IL écrit ({@code Missing elements in vertex: UV0,
     *        UV2} sinon, crash dans {@code GuiRenderer.prepare}).
     * @param vertexFormatOverride format à utiliser à la place de celui de la
     *        référence — {@code null} pour le copier.
     */
    public static Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
            String[] samplerNames, String[] uniformBufferNames,
            Object referencePipeline, Object vertexFormatOverride) {
        return gpu().buildPipeline(location, vertexShaderId, fragmentShaderId,
            samplerNames, uniformBufferNames, referencePipeline, vertexFormatOverride);
    }

    /** Source GLSL répondant {@code vertexGlsl}/{@code fragmentGlsl} pour ces deux identifiants, {@code null} sinon. */
    public static Object shaderSource(Object vertexId, String vertexGlsl, Object fragmentId, String fragmentGlsl) {
        return gpu().shaderSource(vertexId, vertexGlsl, fragmentId, fragmentGlsl);
    }

    /**
     * Précompile {@code pipeline} — à rappeler avant CHAQUE usage : no-op si
     * déjà en cache, indispensable après un rechargement de ressources (F3+T)
     * qui vide le cache de pipelines du device.
     */
    public static void precompile(Object device, Object pipeline, Object source) {
        gpu().precompile(device, pipeline, source);
    }
}
