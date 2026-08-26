package com.yuyuframe.launcheragent.apigraphic.shader;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Infrastructure réutilisable pour construire un {@code RenderPipeline}
 * ENTIÈREMENT maison (roadmap Phase 5, "pipeline shader maison") sur l'ère
 * Blaze3D (26.1.2/1.21.6+) — sans jamais réutiliser
 * {@code RenderPipelines.GUI_TEXT} pour le GLSL lui-même (voir
 * {@link UiSolidPipelinePoc} pour la première pipeline concrète).
 *
 * Mécanisme (vérifié par {@code javap} sur le VRAI jar client 26.1.2, jamais
 * supposé — cf. {@code GpuDevice.precompilePipeline(RenderPipeline)} ET
 * {@code precompilePipeline(RenderPipeline, ShaderSource)}) : la 2e
 * surcharge accepte un {@link com.mojang.blaze3d.shaders.ShaderSource}
 * (interface fonctionnelle à une seule méthode {@code String get(Identifier,
 * ShaderType)}), qu'on implémente ici via {@link Proxy}. Ça contourne
 * ENTIÈREMENT le problème de découverte de ressources (pas de
 * {@code PackResources}/{@code fabric-resource-loader-v0} nécessaire) :
 * le device demande la source GLSL pour un {@code Identifier} donné, notre
 * proxy répond directement avec le texte embarqué en Java — jamais via le
 * {@code ResourceManager} du jeu. Même technique que la lib de référence
 * UniversalCraft (successeur d'Elementa) sur cette même ère, confirmée par
 * lecture de son {@code URenderPipeline.kt} (voir historique de session).
 *
 * Format de sommets / état couleur / profondeur / cull : COPIÉS depuis
 * {@code RenderPipelines.GUI_TEXT} (le pipeline déjà connu-fonctionnel sur ce
 * bracket, voir {@code UiTextBlaze3D}) au lieu d'être reconstruits à la main —
 * seul le GLSL change entre "notre" pipeline et celui de vanilla, jamais la
 * géométrie/l'état GPU, pour rester dans un état connu-fonctionnel pendant
 * cette phase de preuve de mécanisme.
 *
 * Compile en pure réflexion (aucun import direct {@code com.mojang.blaze3d.*}
 * / {@code net.minecraft.*}) — même convention que le reste du projet (voir
 * {@code UiTextBlaze3D}), classpath de compilation sans jar Minecraft.
 */
public final class ShaderPipelineFactory {
    private ShaderPipelineFactory() {}

    private static boolean resolveAttempted, resolveOk;
    private static String currentStage = "";

    private static Class<?> clsRenderSystem, clsGpuDevice, clsRenderPipeline, clsBuilder, clsSnippet,
        clsIdentifier, clsVertexFormat, clsVertexFormatMode, clsColorTargetState, clsDepthStencilState,
        clsShaderSource, clsShaderType, clsRenderPipelines, clsUniformType;

    private static Method mGetDevice, mBuilderStatic, mWithLocation, mWithVertexShader, mWithFragmentShader,
        mWithVertexFormat, mWithColorTargetState, mWithDepthStencilState, mWithCull, mWithSampler, mWithUniform, mBuild,
        mIdentifierOf, mPrecompilePipeline,
        mGetVertexFormat, mGetVertexFormatMode, mGetColorTargetState, mGetDepthStencilState, mIsCull;

    private static Object fieldUniformTypeUniformBuffer;

    private static Object fieldRenderPipelineGuiText;

    /** {@code true} seulement si les classes Blaze3D existent sur ce bracket (26.1.2/1.21.6+) — voir {@code UiTextBlaze3D.isAvailable()}, même convention. */
    public static boolean isAvailable() {
        return McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice") != null;
    }

    private static synchronized boolean resolve() {
        if (resolveAttempted) return resolveOk;
        resolveAttempted = true;
        try {
            currentStage = "rawClass (com.mojang.blaze3d.*)";
            clsRenderSystem = McReflect.rawClass("com.mojang.blaze3d.systems.RenderSystem");
            clsGpuDevice = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice");
            clsRenderPipeline = McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            // Classes IMBRIQUÉES mais résolues directement par nom réel : sans
            // risque de remapping ici puisque isAvailable() garantit qu'on est
            // sur un bracket où Yarn n'est JAMAIS chargé (GpuDevice n'existe
            // qu'à partir de 1.21.6, bien après l'abandon de l'obfuscation
            // Yarn — voir VersionBracketRegistry) — même raisonnement que
            // UiTextBlaze3D pour son propre usage de RenderSystem$a.
            clsBuilder = McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline$Builder");
            clsSnippet = McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline$Snippet");
            clsVertexFormat = McReflect.rawClass("com.mojang.blaze3d.vertex.VertexFormat");
            clsVertexFormatMode = McReflect.rawClass("com.mojang.blaze3d.vertex.VertexFormat$Mode");
            clsColorTargetState = McReflect.rawClass("com.mojang.blaze3d.pipeline.ColorTargetState");
            clsDepthStencilState = McReflect.rawClass("com.mojang.blaze3d.pipeline.DepthStencilState");
            clsShaderSource = McReflect.rawClass("com.mojang.blaze3d.shaders.ShaderSource");
            clsShaderType = McReflect.rawClass("com.mojang.blaze3d.shaders.ShaderType");
            clsUniformType = McReflect.rawClass("com.mojang.blaze3d.shaders.UniformType");

            currentStage = "yarnClass (Identifier/RenderPipelines)";
            clsIdentifier = McReflect.yarnClass("net/minecraft/util/Identifier", "net.minecraft.resources.Identifier");
            clsRenderPipelines = McReflect.yarnClass("net/minecraft/client/gl/RenderPipelines", "net.minecraft.client.renderer.RenderPipelines");

            if (clsRenderSystem == null || clsGpuDevice == null || clsRenderPipeline == null || clsBuilder == null
                    || clsSnippet == null || clsVertexFormat == null || clsVertexFormatMode == null
                    || clsColorTargetState == null || clsDepthStencilState == null || clsShaderSource == null
                    || clsShaderType == null || clsIdentifier == null || clsRenderPipelines == null || clsUniformType == null) {
                throw new ClassNotFoundException("une classe Blaze3D/Identifier/RenderPipelines requise est introuvable");
            }

            currentStage = "methods (RenderSystem/RenderPipeline.Builder/GpuDevice)";
            mGetDevice = clsRenderSystem.getMethod("getDevice");
            // builder(Snippet...) — un seul paramètre de type tableau (Snippet[]).
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
            mPrecompilePipeline = clsGpuDevice.getMethod("precompilePipeline", clsRenderPipeline, clsShaderSource);
            fieldUniformTypeUniformBuffer = clsUniformType.getField("UNIFORM_BUFFER").get(null);

            mGetVertexFormat = clsRenderPipeline.getMethod("getVertexFormat");
            mGetVertexFormatMode = clsRenderPipeline.getMethod("getVertexFormatMode");
            mGetColorTargetState = clsRenderPipeline.getMethod("getColorTargetState");
            mGetDepthStencilState = clsRenderPipeline.getMethod("getDepthStencilState");
            mIsCull = clsRenderPipeline.getMethod("isCull");

            // Identifier.of(String,String) — nom Yarn valable sur les brackets
            // obfusqués antérieurs ; sur 26.1.2 (Yarn jamais chargé), la VRAIE
            // méthode a été renommée fromNamespaceAndPath(String,String) —
            // vérifié par javap sur le jar client 26.1.2 réel (aucune méthode
            // "of" n'existe du tout sur net.minecraft.resources.Identifier).
            currentStage = "Identifier.of/fromNamespaceAndPath";
            if (MappingsRegistry.isLoaded()) {
                mIdentifierOf = clsIdentifier.getMethod(
                    MappingsRegistry.getObfMethodName("net/minecraft/util/Identifier", "of"), String.class, String.class);
            } else {
                mIdentifierOf = clsIdentifier.getMethod("fromNamespaceAndPath", String.class, String.class);
            }

            currentStage = "RenderPipelines.GUI_TEXT";
            fieldRenderPipelineGuiText = clsRenderPipelines.getField(
                MappingsRegistry.getObfFieldName("net/minecraft/client/gl/RenderPipelines", "GUI_TEXT")).get(null);
            if (fieldRenderPipelineGuiText == null) {
                throw new NoSuchFieldException("RenderPipelines.GUI_TEXT introuvable");
            }

            resolveOk = true;
        } catch (Throwable t) {
            resolveOk = false;
            LauncherLog.err("[LauncherAgent] ShaderPipelineFactory: résolution échouée à l'étape '" + currentStage + "' : " + t);
        }
        return resolveOk;
    }

    /** Équivalent de {@code Identifier.of(namespace, path)} — voir javadoc de classe pour la résolution réelle sur ce bracket. */
    public static Object identifier(String namespace, String path) throws Exception {
        if (!resolve()) throw new IllegalStateException("ShaderPipelineFactory indisponible sur ce bracket");
        return mIdentifierOf.invoke(null, namespace, path);
    }

    /** Équivalent de {@code RenderSystem.getDevice()}. */
    public static Object device() throws Exception {
        if (!resolve()) throw new IllegalStateException("ShaderPipelineFactory indisponible sur ce bracket");
        return mGetDevice.invoke(null);
    }

    /**
     * Construit un {@code RenderPipeline} maison référencé par {@code
     * vertexShaderId}/{@code fragmentShaderId} (résolus plus tard via le
     * {@link #shaderSource} fourni à {@link #precompile}) — format de
     * sommets/état couleur/profondeur/cull copiés depuis {@code
     * RenderPipelines.GUI_TEXT} (voir javadoc de classe). Le pipeline n'est
     * PAS encore utilisable pour dessiner tant que {@link #precompile} n'a
     * pas été appelé avec un {@code ShaderSource} répondant à ces deux
     * identifiants.
     */
    public static Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId) throws Exception {
        return buildPipeline(location, vertexShaderId, fragmentShaderId, new String[0], new String[0]);
    }

    /**
     * Comme {@link #buildPipeline(String, Object, Object)} mais déclare en
     * plus des samplers ({@code samplerNames}, ex: {@code "Sampler0"}) et des
     * blocs d'uniform de type {@code UNIFORM_BUFFER} ({@code
     * uniformBufferNames}, ex: {@code "DynamicTransforms"}/{@code
     * "Projection"}) — nécessaire pour tout GLSL qui échantillonne une
     * texture ou lit un bloc {@code layout(std140) uniform ...}. Déclarés
     * EXPLICITEMENT ici (pas copiés génériquement depuis {@code
     * RenderPipelines.GUI_TEXT.getUniforms()}) : GUI_TEXT déclare aussi un
     * bloc {@code Fog} que notre GLSL simplifié n'implémente pas — copier
     * TOUT aurait risqué un mismatch de validation entre pipeline déclaré et
     * shader réellement compilé.
     */
    public static Object buildPipeline(String location, Object vertexShaderId, Object fragmentShaderId,
            String[] samplerNames, String[] uniformBufferNames) throws Exception {
        if (!resolve()) throw new IllegalStateException("ShaderPipelineFactory indisponible sur ce bracket");
        Object emptySnippets = Array.newInstance(clsSnippet, 0);
        Object builder = mBuilderStatic.invoke(null, new Object[]{ emptySnippets });
        builder = mWithLocation.invoke(builder, identifier("yuyuframe", location));
        builder = mWithVertexShader.invoke(builder, vertexShaderId);
        builder = mWithFragmentShader.invoke(builder, fragmentShaderId);
        for (String sampler : samplerNames) {
            builder = mWithSampler.invoke(builder, sampler);
        }
        for (String uniformBuffer : uniformBufferNames) {
            builder = mWithUniform.invoke(builder, uniformBuffer, fieldUniformTypeUniformBuffer);
        }

        Object refVertexFormat = mGetVertexFormat.invoke(fieldRenderPipelineGuiText);
        Object refVertexFormatMode = mGetVertexFormatMode.invoke(fieldRenderPipelineGuiText);
        Object refColorTargetState = mGetColorTargetState.invoke(fieldRenderPipelineGuiText);
        Object refDepthStencilState = mGetDepthStencilState.invoke(fieldRenderPipelineGuiText);
        boolean refCull = (Boolean) mIsCull.invoke(fieldRenderPipelineGuiText);

        builder = mWithVertexFormat.invoke(builder, refVertexFormat, refVertexFormatMode);
        builder = mWithColorTargetState.invoke(builder, refColorTargetState);
        builder = mWithDepthStencilState.invoke(builder, refDepthStencilState);
        builder = mWithCull.invoke(builder, refCull);
        return mBuild.invoke(builder);
    }

    /**
     * Construit un {@code ShaderSource} (proxy dynamique, voir javadoc de
     * classe) qui répond {@code vertexGlsl} pour {@code vertexId} et {@code
     * fragmentGlsl} pour {@code fragmentId}, {@code null} sinon (aucun repli
     * sur le {@code ShaderLoader} du jeu — inutile ici, ce {@code
     * ShaderSource} n'est utilisé QUE pour précompiler CE pipeline, jamais
     * interrogé pour un autre identifiant). Précédent direct dans ce projet
     * pour un {@code Proxy} sur une interface fonctionnelle du jeu :
     * {@code UiVanillaItemRenderer.java} (proxy {@code java.util.function.Function}).
     */
    public static Object shaderSource(Object vertexId, String vertexGlsl, Object fragmentId, String fragmentGlsl) throws Exception {
        if (!resolve()) throw new IllegalStateException("ShaderPipelineFactory indisponible sur ce bracket");
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "get":
                    Object id = args[0];
                    if (vertexId.equals(id)) return vertexGlsl;
                    if (fragmentId.equals(id)) return fragmentGlsl;
                    return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return "ShaderPipelineFactory$ShaderSourceProxy";
            }
        };
        return Proxy.newProxyInstance(clsShaderSource.getClassLoader(), new Class<?>[]{ clsShaderSource }, handler);
    }

    /** Précompile {@code pipeline} sur {@code device} avec {@code source} — doit être appelé UNE FOIS avant tout {@code setPipeline} utilisant ce pipeline. */
    public static void precompile(Object device, Object pipeline, Object source) throws Exception {
        if (!resolve()) throw new IllegalStateException("ShaderPipelineFactory indisponible sur ce bracket");
        mPrecompilePipeline.invoke(device, pipeline, source);
    }
}
