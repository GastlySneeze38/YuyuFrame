package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Infrastructure partagée du rendu Blaze3D era E (26.1.2/1.21.6+) — résolution
 * réflexion (~80 handles Class/Method/Field/Constructor mis en cache une
 * seule fois dans {@link #resolve()}), pipeline "maison" texte/rect/icône
 * ({@code HOME_VERTEX_SRC}/{@code HOME_FRAGMENT_SRC}), helpers de géométrie
 * (masque de coin arrondi, buffers persistants), et la file de dessin différé
 * PARTAGÉE ({@link #enqueue}/{@link #flushQueued()}) qui garantit l'ordre Z
 * entre {@link Blaze3DText}/{@link Blaze3DRect}/{@link Blaze3DGradient} —
 * scindé depuis l'ancien {@code UiTextBlaze3D.java} (2208 lignes, tout dans
 * un seul fichier), même granularité que {@code GlBridge} pour les backends
 * GL legacy/moderne (voir {@code UiPrimitiveRenderer}/{@code UiTextRenderer}).
 *
 * Compile en pure réflexion (pas d'import direct {@code com.mojang.blaze3d.*})
 * — voir la javadoc d'origine de {@code UiTextBlaze3D} (historique complet du
 * choix de ce pipeline vs appels GL bruts) pour le contexte, toujours valable.
 */
public final class Blaze3DCore {
    private Blaze3DCore() {}

    static Boolean available;

    /** {@code true} seulement si les classes Blaze3D (GpuDevice etc.) existent sur ce bracket — sinon, laisser {@link UiRenderer} retomber sur son pipeline SDF existant. */
    public static boolean isAvailable() {
        if (available == null) {
            available = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice") != null;
        }
        return available;
    }

    // ── Classes/méthodes résolues paresseusement, mises en cache ────────────

    static Class<?> clsRenderSystem, clsGpuDevice, clsCommandEncoder, clsRenderPass,
        clsGpuTexture, clsGpuTextureView, clsTextureFormat, clsGpuBuffer, clsGpuBufferSlice,
        clsFilterMode, clsRenderPipeline, clsSamplerCache, clsGpuSampler,
        clsRenderPipelines, clsDynamicUniforms, clsFramebuffer, clsNativeImage, clsNativeImageFormat,
        clsMatrix4f, clsVector4f, clsVector3f;

    static Method mGetDevice, mGetSamplerCache, mSamplerCacheGet,
        mCreateTexture, mCreateTextureView, mCreateBuffer, mCreateBufferSized, mCreateCommandEncoder,
        mWriteToTexture, mWriteToTextureMip, mWriteToBuffer, mBufferSlice, mCreateRenderPass, mSetPipeline, mBindTexture, mSetUniformSlice,
        mSetVertexBuffer, mDraw, mClosePass, mBindDefaultUniforms, mGetDynamicUniforms,
        mDynamicUniformsWrite, mGetColorAttachmentView, mNativeImageSetColor,
        mShapeIndexBufferGetBuffer, mShapeIndexBufferGetType, mSetIndexBuffer, mDrawIndexed,
        mGetProjectionMatrixBuffer, mDisableScissor;

    static java.lang.reflect.Field fieldSharedSequentialQuad;
    static Object sharedSequentialQuad;

    static Constructor<?> ctorNativeImage, ctorVector4f, ctorVector3f;

    static Object fieldFilterModeLinear, fieldTextureFormatRgba8,
        fieldNativeImageFormatRgba;

    static int usageTextureBinding, usageTextureCopyDst, usageTextureRenderAttachment, usageBufferVertex, usageBufferCopyDst, usageBufferUniform;

    /** Fermeture d'une {@code GpuTexture} (AutoCloseable, vérifié par javap) — utilisée par {@code Blaze3DBlur} pour libérer sa chaîne de cibles de rendu hors-écran quand le viewport change de taille. */
    static Method mCloseTexture;

    static Method mMatrixSetOrtho, mMatrixGetFloatArray;

    static boolean resolveAttempted, resolveOk;

    // ── Pipeline shader maison (roadmap Phase 5, remplace RenderPipelines.GUI_TEXT — voir ShaderPipelineFactory) ──

    /**
     * Copie quasi-verbatim de {@code assets/minecraft/shaders/core/
     * rendertype_text.vsh} (extrait du vrai jar client 26.1.2, jamais
     * deviné) — le shader que {@code RenderPipelines.GUI_TEXT} utilise
     * réellement, MOINS le fog ({@code fog.glsl}/{@code
     * FogEnvironmentalStart}/etc., jamais visible sur de l'UI 2D
     * orthographique, absent même du {@code gui.fsh} plus simple de
     * vanilla — simplification sûre). {@code sample_lightmap(Sampler2, UV2)}
     * inliné en {@code texelFetch(Sampler2, UV2 / 16, 0)} — implémentation
     * réelle déjà identifiée lors du bug historique UV2/lightmap (voir plus
     * bas, ensureWhiteTexture) : {@code Sampler2} reste bindé à une texture
     * blanche 1×1 + {@code UV2=(0,0)}, donc ce facteur vaut toujours 1 — le
     * neutraliser en dur ici serait un raccourci correct AUJOURD'HUI, mais
     * le garder réel préserve la possibilité future d'un vrai lightmap.
     * Attributs ({@code Position}/{@code Color}/{@code UV0}/{@code UV2}) :
     * NOMS EXACTS attendus par le {@code VertexFormat} copié depuis {@code
     * GUI_TEXT} (voir {@link ShaderPipelineFactory#buildPipeline}) — Blaze3D
     * lie les attributs de sommet par NOM, jamais par position fixe.
     */
    static final String HOME_VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV2;\n" +
        "uniform sampler2D Sampler2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 texCoord0;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);\n" +
        "    texCoord0 = UV0;\n" +
        "}\n";

    /** Copie quasi-verbatim de {@code core/rendertype_text.fsh} (même source réelle, même simplification sans fog) — voir {@link #HOME_VERTEX_SRC}. */
    static final String HOME_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 texCoord0;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;\n" +
        "    if (color.a < 0.1) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    static Object homePipeline, homeShaderSource;

    // ── Pipeline rect/dégradé-simple à coins arrondis ANALYTIQUES (remplace le masque-texture, voir project_home_shader_pipeline) ──

    /**
     * BUG TROUVÉ (retour utilisateur, capture à l'appui : coins toujours
     * "crénelés/flous" comparés au 1.8.9 MALGRÉ le fix mipmap de {@code
     * ensureCornerMaskTexture}) : un masque BAKÉ EN TEXTURE, aussi bien
     * suréchantillonné soit-il, reste à résolution FINIE — il finit toujours
     * par montrer son grain de texel dès qu'on zoome assez (magnification),
     * contrairement à une distance calculée ANALYTIQUEMENT PAR PIXEL (1.8.9,
     * {@code UiPrimitiveRenderer.FRAGMENT_SRC}), exacte à N'IMPORTE QUEL
     * zoom. Ce pipeline porte cette même formule ici — {@code Position}+
     * {@code Color} suffisent (pas de sampler du tout, {@code Sampler0}/
     * {@code Sampler2} disparaissent pour ce chemin).
     */
    static final String RECT_VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec4 Color;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 fragPos;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    fragPos = Position.xy;\n" +
        "}\n";

    /**
     * Rayon PAR COIN (retour utilisateur : le hack "2 rects superposés, un
     * arrondi + un plat par-dessus" pour simuler un rayon par coin — voir
     * {@code UiMainMenuScreen#drawIconGrid} — causait des artefacts de
     * chevauchement). SDF boîte-arrondie multi-rayon d'Inigo Quilez
     * (iquilezles.org/articles/distfunctions2d, {@code sdRoundedBox}) au lieu
     * du clamp mono-rayon précédent — généralisation stricte : avec les 4
     * rayons égaux, produit EXACTEMENT le même alpha que l'ancienne formule
     * (vérifié par calcul : {@code dist_new = dist_old - radius}, bandes de
     * transition équivalentes par décalage) donc AUCUN changement visuel sur
     * tout ce qui était déjà validé "parfait"/"beaucoup mieux". Corrige aussi
     * radius=0 nativement (contrairement au clamp mono-rayon) : au centre,
     * {@code q} est très négatif des deux côtés → dist très négatif → alpha=1
     * ; seul le pixel EXACTEMENT sur le coin mathématique (jamais le centre
     * d'un pixel réel, toujours décalé de 0.5px) friserait alpha=0.5, un
     * artefact d'antialiasing normal et mineur, pas le "invisible partout" du
     * clamp mono-rayon à radius=0.
     *
     * {@code u_CornerRadii = (topLeft, topRight, bottomLeft, bottomRight)} —
     * "top"/"bottom" au sens du COIN LE PLUS PROCHE DE y0/y1 tels que passés
     * par l'appelant (y0 < y1 toujours dans tous les appelants de ce moteur),
     * PAS une convention d'axe GL absolue — robuste quel que soit le sens de
     * l'axe Y de la projection (voir le bug historique de flip Y sur
     * ensureProjectionBuffer, non pertinent ici puisqu'on compare seulement
     * fragPos au centre DU RECT LUI-MÊME).
     */
    static final String RECT_FRAGMENT_SRC =
        "#version 330\n" +
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
        "in vec4 vertexColor;\n" +
        "in vec2 fragPos;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 p = fragPos - center;\n" +
        "    float radius = (p.x > 0.0)\n" +
        "        ? ((p.y < 0.0) ? u_CornerRadii.y : u_CornerRadii.w)\n" +
        "        : ((p.y < 0.0) ? u_CornerRadii.x : u_CornerRadii.z);\n" +
        "    vec2 q = abs(p) - halfSize + vec2(radius);\n" +
        "    float dist = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    vec4 color = vertexColor * ColorModulator;\n" +
        "    color.a *= alpha;\n" +
        "    if (color.a < 0.01) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    static Object rectPipeline, rectShaderSource;
    static Object rectParamsBuffer;

    // ── Pipeline de rects BATCHÉS (roadmap Phase 5.5) ───────────────────────
    //
    // Regroupe N rects (bornes/couleur différentes, MÊME rayon partagé) en UN
    // SEUL draw call — contrairement à RECT_FRAGMENT_SRC (u_Rect/u_CornerRadii
    // en UNIFORM, donc un seul rect par draw), ce pipeline calcule le SDF en
    // espace LOCAL au quad : UV0 = coordonnée normalisée 0..1 dans le quad,
    // UV2 (déjà présent dans le format de sommet hérité de GUI_TEXT, jamais
    // utilisé par rectPipeline) réutilisé pour porter la taille RÉELLE en
    // pixels du rect (short×2 — largeur/hauteur), lue par sommet. Un rayon
    // PARTAGÉ (pas par coin) reste en uniform : aucune place restante dans le
    // format PCTL 28 octets (Position 12 + Color 4 + UV0 8 + UV2 4 = 28,
    // déjà plein) pour un rayon par sommet — un batch ne peut donc mélanger
    // que des rects de MÊME rayon (contrainte acceptée, cas le plus courant :
    // toutes les cartes d'un écran partagent RADIUS_MD).
    static final String RECT_BATCH_VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 localUV;\n" +
        "out vec2 rectSize;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localUV = UV0;\n" +
        "    rectSize = vec2(UV2);\n" +
        "}\n";

    /** Même formule SDF que {@link #RECT_FRAGMENT_SRC} (rayon uniforme, pas par coin) mais en espace LOCAL au quad (voir {@link #RECT_BATCH_VERTEX_SRC}) — {@code BatchParams.x} = rayon PARTAGÉ par tout le batch. */
    static final String RECT_BATCH_FRAGMENT_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform BatchParams {\n" +
        "    vec4 u_Radius;\n" +
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localUV;\n" +
        "in vec2 rectSize;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    float radius = u_Radius.x;\n" +
        "    vec2 halfSize = rectSize * 0.5;\n" +
        "    vec2 p = localUV * rectSize - halfSize;\n" +
        "    vec2 q = abs(p) - halfSize + vec2(radius);\n" +
        "    float dist = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    vec4 color = vertexColor * ColorModulator;\n" +
        "    color.a *= alpha;\n" +
        "    if (color.a < 0.01) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    static Object batchPipeline, batchShaderSource;
    static Object batchParamsBuffer;

    static Object ensureBatchParamsBuffer(Object device) throws Exception {
        if (batchParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_rect_batch_params";
            batchParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 16L);
        }
        return batchParamsBuffer;
    }

    static Object writeBatchParams(Object device, Object encoder, float radius) throws Exception {
        Object buffer = ensureBatchParamsBuffer(device);
        ByteBuffer data = ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
        data.putFloat(radius).putFloat(0f).putFloat(0f).putFloat(0f);
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 16L);
        mWriteToBuffer.invoke(encoder, slice, data);
        return slice;
    }

    static Object ensureRectParamsBuffer(Object device) throws Exception {
        if (rectParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_rect_params";
            rectParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 32L);
        }
        return rectParamsBuffer;
    }

    /** Rayon UNIFORME — délègue à la version 4-rayons (tous égaux). */
    static Object writeRectParams(Object device, Object encoder, float x0, float y0, float x1, float y1, float radius) throws Exception {
        return writeRectParams(device, encoder, x0, y0, x1, y1, radius, radius, radius, radius);
    }

    /** Pack {@code RectParams} en std140 (32 octets, tout en vec4 — même précaution que {@code GradientParams}) et réécrit à CHAQUE draw (valeurs changent à chaque appel, contrairement à la projection). {@code radiusTopLeft/TopRight/BottomLeft/BottomRight} — voir {@link #RECT_FRAGMENT_SRC} pour la convention top/bottom (relative à y0/y1, pas à l'axe GL). */
    static Object writeRectParams(Object device, Object encoder, float x0, float y0, float x1, float y1,
                                   float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight) throws Exception {
        Object buffer = ensureRectParamsBuffer(device);
        ByteBuffer data = ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder());
        data.putFloat(x0).putFloat(y0).putFloat(x1).putFloat(y1);
        data.putFloat(radiusTopLeft).putFloat(radiusTopRight).putFloat(radiusBottomLeft).putFloat(radiusBottomRight);
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 32L);
        mWriteToBuffer.invoke(encoder, slice, data);
        return slice;
    }

    /**
     * Résout une classe via Yarn si chargé (obfuscation classique,
     * comportement INCHANGÉ), SINON (bracket 26.1+, jeu non obfusqué, Yarn
     * jamais chargé — voir VersionBracketRegistry) directement par le nom
     * réel fourni — chaque nom ici vérifié par {@code javap} sur le jar
     * client 26.1.2 réel (jamais deviné par simple renommage de convention,
     * voir le commentaire de classe pour la même exigence appliquée au
     * pipeline lui-même).
     */
    static Class<?> resolveYarnOrReal(String yarnName, String realBinaryName) {
        Class<?> c = McReflect.yarnClass(yarnName);
        if (c != null) return c;
        return McReflect.rawClass(realBinaryName);
    }
    static synchronized boolean resolve() {
        if (resolveAttempted) return resolveOk;
        resolveAttempted = true;
        try {
            clsRenderSystem = McReflect.rawClass("com.mojang.blaze3d.systems.RenderSystem");
            clsGpuDevice = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice");
            clsCommandEncoder = McReflect.rawClass("com.mojang.blaze3d.systems.CommandEncoder");
            clsRenderPass = McReflect.rawClass("com.mojang.blaze3d.systems.RenderPass");
            clsGpuTexture = McReflect.rawClass("com.mojang.blaze3d.textures.GpuTexture");
            clsGpuTextureView = McReflect.rawClass("com.mojang.blaze3d.textures.GpuTextureView");
            clsTextureFormat = McReflect.rawClass("com.mojang.blaze3d.textures.TextureFormat");
            clsGpuBuffer = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBuffer");
            clsGpuBufferSlice = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBufferSlice");
            clsFilterMode = McReflect.rawClass("com.mojang.blaze3d.textures.FilterMode");
            clsRenderPipeline = McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            clsMatrix4f = McReflect.rawClass("org.joml.Matrix4f");
            clsVector4f = McReflect.rawClass("org.joml.Vector4f");
            clsVector3f = McReflect.rawClass("org.joml.Vector3f");

            // Classes obfusquées (net.minecraft) — résolution Yarn dynamique, jamais de nom obf codé en dur.
            // McReflect.yarnClass (pas MappingsRegistry.loadClass directement) : même
            // convention que le chemin NativeImage déjà éprouvé dans UiRenderer
            // (ensureNativeTextureApiResolved) — évite toute divergence de comportement.
            // resolveYarnOrReal (pas McReflect.yarnClass seul) : sur le bracket
            // 26.1+ (Yarn jamais chargé), ces classes ont TOUTES changé de
            // package par rapport à la convention Yarn "net/minecraft/client/gl/*"
            // — confirmé par javap sur le jar client 26.1.2 réel :
            //   SamplerCache/GpuSampler  → com.mojang.blaze3d.{systems,textures}
            //   RenderPipelines/DynamicUniforms → net.minecraft.client.renderer
            //   Framebuffer → com.mojang.blaze3d.pipeline.RenderTarget (renommée aussi)
            //   NativeImage(.Format) → com.mojang.blaze3d.platform
            clsSamplerCache = resolveYarnOrReal("net/minecraft/client/gl/SamplerCache", "com.mojang.blaze3d.systems.SamplerCache");
            clsGpuSampler = resolveYarnOrReal("net/minecraft/client/gl/GpuSampler", "com.mojang.blaze3d.textures.GpuSampler");
            clsRenderPipelines = resolveYarnOrReal("net/minecraft/client/gl/RenderPipelines", "net.minecraft.client.renderer.RenderPipelines");
            clsDynamicUniforms = resolveYarnOrReal("net/minecraft/client/gl/DynamicUniforms", "net.minecraft.client.renderer.DynamicUniforms");
            clsFramebuffer = resolveYarnOrReal("net/minecraft/client/gl/Framebuffer", "com.mojang.blaze3d.pipeline.RenderTarget");
            clsNativeImage = resolveYarnOrReal("net/minecraft/client/texture/NativeImage", "com.mojang.blaze3d.platform.NativeImage");
            clsNativeImageFormat = resolveYarnOrReal("net/minecraft/client/texture/NativeImage$Format", "com.mojang.blaze3d.platform.NativeImage$Format");
            if (clsSamplerCache == null || clsGpuSampler == null || clsRenderPipelines == null
                    || clsDynamicUniforms == null || clsFramebuffer == null || clsNativeImage == null
                    || clsNativeImageFormat == null) {
                throw new ClassNotFoundException("une classe net.minecraft requise est introuvable (voir logs Yarn)");
            }

            mGetDevice = clsRenderSystem.getMethod("getDevice");
            mGetSamplerCache = clsRenderSystem.getMethod("getSamplerCache");
            mBindDefaultUniforms = clsRenderSystem.getMethod("bindDefaultUniforms", clsRenderPass);
            mGetDynamicUniforms = clsRenderSystem.getMethod("getDynamicUniforms");

            // BUG TROUVÉ (utilisateur : mipmaps générés — voir ensureTexture —
            // mais texte "toujours pixelisé", quasi aucun effet visible) :
            // désassemblage de SamplerCache.init() (pas deviné) — le
            // paramètre booléen "defaultLineOfDetail" de la surcharge
            // get(FilterMode,boolean) décide, à la construction du VRAI
            // GpuSampler (GpuDevice.createSampler(...,maxAniso,lodOption)) :
            // false → OptionalDouble.of(0.0) = LOD FIGÉ à 0 (ignore TOUS les
            // niveaux de mip, quels qu'ils soient) ; true →
            // OptionalDouble.empty() = plage de LOD dynamique COMPLÈTE
            // (sélection automatique du mip selon l'empreinte écran — ce
            // qu'on veut). La surcharge à 1 argument get(FilterMode), qu'on
            // utilisait, appelle EN INTERNE get(FilterMode, false) (confirmé
            // par désassemblage direct de son bytecode) — notre sampler
            // était donc VERROUILLÉ sur le mip 0, rendant TOUTE la chaîne de
            // mips générée totalement inutilisée par le GPU. Fix : résoudre
            // ET utiliser la surcharge à 2 arguments avec `true`.
            // 26.1+ (Yarn non chargé) : SamplerCache.get(FilterMode,boolean)
            // N'EXISTE PLUS DU TOUT — API redessinée (vérifié par javap sur
            // le jar client 26.1.2 réel) : getSampler(AddressMode,AddressMode,
            // FilterMode,FilterMode,boolean), getClampToEdge(FilterMode[,boolean]),
            // getRepeat(FilterMode[,boolean]). getClampToEdge(FilterMode,boolean)
            // est l'équivalent le plus proche pour une texture d'atlas non
            // répétée (même forme FilterMode+boolean, sémantique "clamp"
            // cohérente avec un atlas 2D) — INFÉRÉ par correspondance de
            // forme/nom, PAS vérifié comportementalement (le booléen
            // active-t-il bien la même plage de LOD dynamique complète que
            // sur l'ancienne API ? à confirmer en jeu, voir le BUG TROUVÉ
            // juste au-dessus pour l'enjeu si jamais ce n'était pas le cas).
            if (MappingsRegistry.isLoaded()) {
                String getSamplerName = MappingsRegistry.getObfMethodName(
                    "net/minecraft/client/gl/SamplerCache", "get", "(Lcom/mojang/blaze3d/textures/FilterMode;Z)Lfzf;");
                mSamplerCacheGet = clsSamplerCache.getMethod(getSamplerName, clsFilterMode, boolean.class);
            } else {
                mSamplerCacheGet = clsSamplerCache.getMethod("getClampToEdge", clsFilterMode, boolean.class);
            }

            mGetProjectionMatrixBuffer = clsRenderSystem.getMethod("getProjectionMatrixBuffer");

            mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
            mCreateTexture = clsGpuDevice.getMethod("createTexture",
                java.util.function.Supplier.class, int.class, clsTextureFormat, int.class, int.class, int.class, int.class);
            mCreateTextureView = clsGpuDevice.getMethod("createTextureView", clsGpuTexture);
            mCreateBuffer = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, ByteBuffer.class);
            mCreateBufferSized = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, long.class);
            mBufferSlice = clsGpuBuffer.getMethod("slice", long.class, long.class);

            mWriteToTexture = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage);
            // Variante détaillée (mipLevel explicite) — vérifiée dans les
            // mappings Yarn officiels (pas devinée) : writeToTexture(target,
            // source, mipLevel, depth, offsetX, offsetY, width, height,
            // skipPixels, skipRows). Nécessaire pour uploader les niveaux de
            // mipmap de l'atlas de police (voir BUG TROUVÉ dans ensureTexture).
            mWriteToTextureMip = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
            mWriteToBuffer = clsCommandEncoder.getMethod("writeToBuffer", clsGpuBufferSlice, ByteBuffer.class);
            mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass",
                java.util.function.Supplier.class, clsGpuTextureView, OptionalInt.class);

            mSetPipeline = clsRenderPass.getMethod("setPipeline", clsRenderPipeline);
            mBindTexture = clsRenderPass.getMethod("bindTexture", String.class, clsGpuTextureView, clsGpuSampler);
            mSetUniformSlice = clsRenderPass.getMethod("setUniform", String.class, clsGpuBufferSlice);
            mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
            mDraw = clsRenderPass.getMethod("draw", int.class, int.class);
            mClosePass = clsRenderPass.getMethod("close");
            // DIAGNOSTIC (26.1+, tout le reste — pipeline/format/coords/couleur —
            // vérifié sain sans expliquer l'invisibilité totale) : RenderPass
            // expose désormais enableScissor(IIII)/disableScissor() (absents de
            // l'ancien bracket) — si le scissor par défaut d'une passe fraîche
            // n'est plus "plein cadre" sur ce backend, tout serait découpé à
            // zéro sans la moindre exception. Appelé explicitement (optionnel :
            // null si absent, degrade proprement) juste après setPipeline.
            try {
                mDisableScissor = clsRenderPass.getMethod("disableScissor");
            } catch (NoSuchMethodException noScissorApi) {
                mDisableScissor = null;
            }

            // BUG TROUVÉ (texte invisible malgré draw() qui "réussit" sans
            // exception) : RenderPipelines.GUI_TEXT utilise le mode
            // VertexFormat.DrawMode.QUADS (vérifié par désassemblage,
            // VertexFormats.POSITION_COLOR_TEXTURE_LIGHT + mode "h"=QUADS) —
            // RenderPass.draw(int,int) (non indexé) ne fait PAS la conversion
            // QUADS→triangles automatiquement ; il faut passer par
            // drawIndexed() avec un buffer d'indices de triangulation, fourni
            // par l'utilitaire PARTAGÉ que Minecraft utilise lui-même pour ça
            // (RenderSystem.sharedSequentialQuad, champ STATIC PRIVATE — pas
            // d'accesseur public, d'où la réflexion sur le champ directement).
            // BUG TROUVÉ (NoSuchMethodException malgré une signature identique
            // caractère pour caractère à celle désassemblée) : contrairement
            // aux classes Blaze3D de PREMIER NIVEAU (com.mojang.blaze3d.*,
            // stables/identiques dans les 3 espaces de noms official/
            // intermediary/named — jamais remappées par Fabric), leurs classes
            // IMBRIQUÉES (VertexFormat$a, RenderSystem$a...) restent, elles,
            // remappées comme le reste du jeu obfusqué : confirmé par
            // introspection directe (getMethods() sur RenderPass réellement
            // chargé) que le VRAI paramètre runtime est nommé
            // "VertexFormat$class_5595" (intermediary), PAS "VertexFormat$a"
            // (official) — le classloader n'était PAS le problème (déjà
            // KnotClassLoader des deux côtés), c'était le NOM utilisé qui
            // était faux sous Fabric. Fix : passer par
            // MappingsRegistry.runtimeClass() (même mécanisme official→
            // runtime déjà utilisé PARTOUT ailleurs dans ce projet pour les
            // classes net.minecraft.* obfusquées) au lieu de supposer ces
            // classes imbriquées stables comme leurs classes conteneuses.
            // Champ déclaré directement sur RenderSystem (classe de PREMIER
            // NIVEAU, stable/inchangée dans les 3 espaces de noms — voir
            // commentaire ci-dessus) : nom littéral direct, pas de remapping
            // nécessaire ici (contrairement aux classes imbriquées).
            fieldSharedSequentialQuad = clsRenderSystem.getDeclaredField("sharedSequentialQuad");
            fieldSharedSequentialQuad.setAccessible(true);

            Class<?> clsShapeIndexBuffer;
            Class<?> clsIndexType;
            if (MappingsRegistry.isLoaded()) {
                // Mêmes méthodes que désassemblées ("b(int)"/"a()", noms OFFICIELS
                // bruts) — mais ce sont des méthodes DÉCLARÉES SUR une classe
                // imbriquée elle-même remappée : leurs noms le sont probablement
                // AUSSI sous Fabric (pas juste le nom de la classe conteneuse).
                // Résolus via runtimeMethod() (official→runtime), même mécanisme
                // que runtimeClass() ci-dessus.
                clsShapeIndexBuffer = Class.forName(
                    MappingsRegistry.runtimeClass("com/mojang/blaze3d/systems/RenderSystem$a"), false, clsRenderPass.getClassLoader());
                String getBufferRuntime = MappingsRegistry.runtimeMethod(
                    "com/mojang/blaze3d/systems/RenderSystem$a", "b", "(I)Lcom/mojang/blaze3d/buffers/GpuBuffer;");
                String getTypeRuntime = MappingsRegistry.runtimeMethod(
                    "com/mojang/blaze3d/systems/RenderSystem$a", "a", "()Lcom/mojang/blaze3d/vertex/VertexFormat$a;");
                mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod(getBufferRuntime, int.class); // getIndexBuffer(int) — assure la capacité et renvoie le GpuBuffer
                mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod(getTypeRuntime); // getIndexType() -> VertexFormat.IndexType
                clsIndexType = Class.forName(
                    MappingsRegistry.runtimeClass("com/mojang/blaze3d/vertex/VertexFormat$a"), false, clsRenderPass.getClassLoader());
            } else {
                // 26.1+ : "RenderSystem$a"/"VertexFormat$a" sont des noms
                // OBFUSQUÉS 1.21.11, sans aucun sens pour un jeu non obfusqué
                // (aucune classe de ce nom n'existe). Plutôt que deviner le
                // VRAI nom de la classe imbriquée (confirmé par javap :
                // "RenderSystem$AutoStorageIndexBuffer", mais ce nom n'est en
                // rien garanti stable version à version), on le DÉRIVE
                // directement du champ déjà résolu ci-dessus par son nom
                // littéral stable "sharedSequentialQuad" — fonctionne quel
                // que soit le nom réel de la classe imbriquée, aujourd'hui ET
                // sur une future version. Méthodes "getBuffer(int)"/"type()"
                // confirmées par javap (noms réels stables, pas obfusqués).
                clsShapeIndexBuffer = fieldSharedSequentialQuad.getType();
                mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod("getBuffer", int.class);
                mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod("type");
                clsIndexType = mShapeIndexBufferGetType.getReturnType();
            }
            mSetIndexBuffer = clsRenderPass.getMethod("setIndexBuffer", clsGpuBuffer, clsIndexType);
            mDrawIndexed = clsRenderPass.getMethod("drawIndexed", int.class, int.class, int.class, int.class);

            // DynamicUniforms.write(Matrix4fc, Vector4fc, Vector3fc, Matrix4fc) — nom Yarn
            // "write" (obf "a") — un seul candidat 4-arg, sûr par nom seul ici.
            for (Method m : clsDynamicUniforms.getMethods()) {
                if (m.getParameterCount() == 4 && clsGpuBufferSlice.isAssignableFrom(m.getReturnType())) {
                    mDynamicUniformsWrite = m;
                    break;
                }
            }

            // 26.1+ : Framebuffer.getColorAttachmentView() renommée
            // getColorTextureView() sur RenderTarget (vérifié par javap sur
            // le jar client 26.1.2 réel — même méthode, no-arg, retourne
            // GpuTextureView).
            mGetColorAttachmentView = MappingsRegistry.isLoaded()
                ? clsFramebuffer.getMethod(MappingsRegistry.getObfMethodName("net/minecraft/client/gl/Framebuffer", "getColorAttachmentView"))
                : clsFramebuffer.getMethod("getColorTextureView");

            // Même chemin, déjà éprouvé, que UiRenderer.ensureNativeTextureApiResolved()
            // (createFontTextureViaNativeImage) — constructeur (Format,int,int,boolean)
            // et méthode "setColor" (nom Yarn stable, PAS "setColorArgb").
            ctorNativeImage = clsNativeImage.getDeclaredConstructor(clsNativeImageFormat, int.class, int.class, boolean.class);
            ctorNativeImage.setAccessible(true);
            // 26.1+ : NativeImage.setColor(int,int,int) N'EXISTE PLUS (vérifié
            // par javap — absent de la classe entière) — remplacée par
            // setPixel(int,int,int) ET setPixelABGR(int,int,int), deux
            // conventions d'octets différentes. PAS une supposition : le
            // format ABGR packé construit plus bas
            // (bufferedImageToNativeImage : "(a<<24)|(b<<16)|(g<<8)|r") est
            // EXACTEMENT ce que le nom "setPixelABGR" décrit — confirmé par
            // le nom de la méthode elle-même, pas deviné par élimination.
            mNativeImageSetColor = MappingsRegistry.isLoaded()
                ? McReflect.method(clsNativeImage, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class)
                : clsNativeImage.getMethod("setPixelABGR", int.class, int.class, int.class);

            String rgbaObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage$Format", "RGBA");
            java.lang.reflect.Field rgbaField = clsNativeImageFormat.getDeclaredField(rgbaObf);
            rgbaField.setAccessible(true);
            fieldNativeImageFormatRgba = rgbaField.get(null);

            fieldFilterModeLinear = clsFilterMode.getField("LINEAR").get(null);
            fieldTextureFormatRgba8 = clsTextureFormat.getField("RGBA8").get(null);

            // Pipeline shader maison (roadmap Phase 5) — REMPLACE
            // RenderPipelines.GUI_TEXT pour le rendu réel, voir
            // ShaderPipelineFactory pour le mécanisme (précompilePipeline +
            // ShaderSource) et HOME_VERTEX_SRC/HOME_FRAGMENT_SRC pour le
            // GLSL. Format de sommets/état couleur/profondeur/cull restent
            // copiés depuis GUI_TEXT par ShaderPipelineFactory lui-même —
            // seul le GLSL change ici. Samplers/uniforms déclarés
            // explicitement (Sampler0/Sampler2/DynamicTransforms/Projection)
            // : exactement les 4 bindings que ce fichier alimente déjà plus
            // bas (mBindTexture/mSetUniformSlice, code inchangé).
            Object vertexId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d.vsh");
            Object fragmentId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d.fsh");
            homePipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d", vertexId, fragmentId,
                new String[]{ "Sampler0", "Sampler2" }, new String[]{ "DynamicTransforms", "Projection" });
            homeShaderSource = ShaderPipelineFactory.shaderSource(vertexId, HOME_VERTEX_SRC, fragmentId, HOME_FRAGMENT_SRC);

            // Pipeline rect/dégradé-simple à coins arrondis analytiques (voir
            // RECT_VERTEX_SRC/RECT_FRAGMENT_SRC) — remplace homePipeline pour
            // drawRect/drawGradientRect/drawGradientRect2D. Aucun sampler
            // (pas de masque-texture, tout est calculé par pixel).
            Object rectVertexId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_rect.vsh");
            Object rectFragmentId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_rect.fsh");
            rectPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_rect", rectVertexId, rectFragmentId,
                new String[0], new String[]{ "DynamicTransforms", "Projection", "RectParams" });
            rectShaderSource = ShaderPipelineFactory.shaderSource(rectVertexId, RECT_VERTEX_SRC, rectFragmentId, RECT_FRAGMENT_SRC);

            // Pipeline rects BATCHÉS (voir RECT_BATCH_VERTEX_SRC/RECT_BATCH_FRAGMENT_SRC) — roadmap Phase 5.5.
            Object batchVertexId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_rect_batch.vsh");
            Object batchFragmentId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_rect_batch.fsh");
            batchPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_rect_batch", batchVertexId, batchFragmentId,
                new String[0], new String[]{ "DynamicTransforms", "Projection", "BatchParams" });
            batchShaderSource = ShaderPipelineFactory.shaderSource(batchVertexId, RECT_BATCH_VERTEX_SRC, batchFragmentId, RECT_BATCH_FRAGMENT_SRC);

            // Pipeline dégradé multi-stop / texte SDF — construits par
            // Blaze3DGradient/Blaze3DText eux-mêmes (le fichier qui possède
            // le GLSL possède aussi le code qui le compile), on vérifie
            // juste ici que ça a réussi.
            boolean gradientPipelineOk = Blaze3DGradient.resolveGradientPipeline();
            boolean textPipelineOk = Blaze3DText.resolveTextPipeline();
            boolean blurPipelineOk = Blaze3DBlur.resolveBlurPipeline();
            boolean blendPipelineOk = Blaze3DBlend.resolveBlendPipeline();

            if (mNativeImageSetColor == null || fieldNativeImageFormatRgba == null || homePipeline == null || rectPipeline == null || batchPipeline == null || !gradientPipelineOk || !textPipelineOk || !blurPipelineOk || !blendPipelineOk
                    || fieldSharedSequentialQuad == null || mShapeIndexBufferGetBuffer == null
                    || mShapeIndexBufferGetType == null || mSetIndexBuffer == null || mDrawIndexed == null
                    || mWriteToTextureMip == null) {
                throw new NoSuchMethodException("setColor/RGBA/homePipeline/sharedSequentialQuad/writeToTextureMip introuvable (voir logs)");
            }

            // BUG TROUVÉ (premier test v373/v374, IllegalStateException) : notre
            // texture d'atlas était créée avec USAGE_TEXTURE_BINDING seul —
            // CommandEncoder.writeToTexture() exige AUSSI USAGE_COPY_DST sur la
            // texture cible ("Color texture must have USAGE_COPY_DST to be a
            // destination for a write"). GpuTexture.USAGE_COPY_DST est une
            // constante DISTINCTE de GpuBuffer.USAGE_COPY_DST (classes
            // différentes, même nom de champ) — bien résoudre celle de GpuTexture ici.
            usageTextureBinding = clsGpuTexture.getField("USAGE_TEXTURE_BINDING").getInt(null);
            usageTextureCopyDst = clsGpuTexture.getField("USAGE_COPY_DST").getInt(null);
            // Vérifié par javap sur GpuTexture.class (jar client 26.1.2 réel) :
            // USAGE_RENDER_ATTACHMENT existe bien, et MainTarget.allocateColorAttachment
            // crée SA PROPRE texture couleur avec usage=15 (COPY_DST|COPY_SRC|
            // TEXTURE_BINDING|RENDER_ATTACHMENT combinés) — confirme que le
            // framebuffer principal du jeu est directement échantillonnable
            // comme Sampler0 (TEXTURE_BINDING inclus), pas besoin d'une copie
            // préalable pour alimenter la 1ère passe de downsample du flou.
            usageTextureRenderAttachment = clsGpuTexture.getField("USAGE_RENDER_ATTACHMENT").getInt(null);
            mCloseTexture = clsGpuTexture.getMethod("close");
            usageBufferVertex = clsGpuBuffer.getField("USAGE_VERTEX").getInt(null);
            usageBufferCopyDst = clsGpuBuffer.getField("USAGE_COPY_DST").getInt(null);
            usageBufferUniform = clsGpuBuffer.getField("USAGE_UNIFORM").getInt(null);

            ctorVector4f = clsVector4f.getConstructor(float.class, float.class, float.class, float.class);
            ctorVector3f = clsVector3f.getConstructor(float.class, float.class, float.class);

            // BUG SUSPECTÉ (aucune exception nulle part dans TOUTE la chaîne —
            // texture/buffer/pass/uniforms/draw indexé tous OK, DIAG-PROJ non-
            // null — mais 0 pixel dessiné, confirmé par capture d'écran) : le
            // "Projection" repris par bindDefaultUniforms(pass) est celui de
            // l'état AMBIANT de Minecraft à notre point d'accroche (fin de
            // frame) — calibré pour SES propres appels (avec un modelView qui
            // inclut probablement un décalage Z que nous ne reproduisons pas,
            // nos sommets utilisant systématiquement z=0 avec un modelView
            // identité). Si le near/far de ce projection ambiant ne couvre pas
            // z=0 (rien ne garantit qu'il le fasse), nos sommets sont
            // silencieusement clippés par le GPU — AUCUNE exception Java ne
            // peut jamais détecter un clipping géométrique, c'est un
            // comportement GPU normal, pas une erreur. Fix : ne plus dépendre
            // de cet état ambiant emprunté pour "Projection" — construire NOTRE
            // PROPRE matrice orthographique 2D (via JOML Matrix4f.setOrtho,
            // near=-1000/far=1000 SYMÉTRIQUE autour de 0 : garantit
            // mathématiquement que z=0 → z_ndc=0, toujours dans [-1,1]) et
            // l'imposer nous-mêmes après bindDefaultUniforms (qui reste utile
            // pour Fog/Globals/Lighting, non touchés ici).
            mMatrixSetOrtho = clsMatrix4f.getMethod("setOrtho",
                float.class, float.class, float.class, float.class, float.class, float.class);
            mMatrixGetFloatArray = clsMatrix4f.getMethod("get", float[].class);

            resolveOk = true;
        } catch (Throwable t) {
            resolveOk = false;
            // Cause réelle DÉROULÉE (pas juste "InvocationTargetException"
            // générique) — piège du silent-catch déjà rencontré ailleurs dans
            // ce projet (voir McReflect.minecraftClient()/getFramebuffer) :
            // un simple "+ t" sur une exception réflexive n'affiche QUE le
            // wrapper, jamais la vraie exception levée par le code du jeu.
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[LauncherAgent] UiTextBlaze3D: résolution échouée, repli sur le pipeline SDF existant : " + t + " | cause réelle : " + cause);
        }
        return resolveOk;
    }

    static Object bufferedImageToNativeImage(BufferedImage img, int w, int h) throws Exception {
        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                mNativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
            }
        }
        return nativeImage;
    }

    // ── Texture blanche 1×1 dédiée à Sampler2 (voir drawText) ───────────────

    static Object[] whiteTexture; // [GpuTexture, GpuTextureView, GpuSampler]

    // BUG TROUVÉ (aucune exception nulle part, projection maison déjà
    // corrigée, TOUJOURS 0 pixel dessiné) : shader RÉEL extrait du jar client
    // 1.21.11 (assets/minecraft/shaders/core/rendertype_text.vsh, jamais
    // deviné) — `vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);`.
    // UV2 (attribut ivec2, PAS une simple donnée décorative "neutralisée
    // ailleurs" comme supposé à tort dans un commentaire précédent) sert à
    // indexer le lightmap via Sampler2. Avec light1=(short)0xF000 (= -4096 en
    // short SIGNÉ), UV2/16 sort de la plage valide (texelFetch hors-limites =
    // comportement indéfini, en pratique (0,0,0,0) sur la plupart des
    // drivers) → vertexColor devient transparent NOIR → le fragment shader
    // (`if (color.a < 0.1) discard;`) rejette CHAQUE fragment. Aucune
    // exception possible : c'est un calcul GPU pur, silencieux par nature.
    // MAX_LIGHT_COORDINATE (0x00F000F0) se décompose RÉELLEMENT en deux
    // moitiés IDENTIQUES 0x00F0/0x00F0 (vérifié par calcul, pas 0x00F0/0xF000
    // comme l'ancien commentaire l'affirmait à tort — l'erreur de "fix"
    // précédente est la cause directe de ce bug). Plutôt que de reproduire
    // l'exacte sémantique du lightmap vanilla (nécessiterait de résoudre et
    // lier SA texture réelle, complexité inutile pour de l'UI toujours
    // plein-jour), on neutralise complètement : Sampler2 lié à une texture
    // DÉDIÉE 1×1 blanc opaque (jamais notre atlas — un texel à l'intérieur du
    // padding de l'atlas n'a AUCUNE garantie d'être opaque) + UV2=(0,0)
    // (texelFetch(..., (0,0), 0) toujours valide, quelle que soit la taille
    // de la texture liée) → `texelFetch(...) = blanc opaque` → `vertexColor =
    // Color * blanc = Color`, inchangé, exactement le comportement voulu.
    static Object[] ensureWhiteTexture() throws Exception {
        if (whiteTexture != null) return whiteTexture;
        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, 1, 1, false);
        mNativeImageSetColor.invoke(nativeImage, 0, 0, 0xFFFFFFFF); // petit-boutiste RGBA — blanc opaque quel que soit l'ordre des octets
        Object device = mGetDevice.invoke(null);
        java.util.function.Supplier<String> label = () -> "yuyuframe_text_white1x1";
        Object texture = mCreateTexture.invoke(device, label, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, 1, 1, 1, 1);
        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);
        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        whiteTexture = new Object[]{texture, textureView, sampler};
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: texture blanche 1x1 (Sampler2) créée");
        return whiteTexture;
    }


    static Object vertexBuffer;
    static long vertexBufferCapacity;

    // ── Buffer de sommets CÔTÉ CPU (staging) PERSISTANT — même esprit que
    // ensureVertexBuffer juste en dessous (côté GPU), pour le buffer côté
    // CPU qu'on remplit AVANT de l'y copier. AUDIT PERF (demandé
    // explicitement par l'utilisateur, "gratter des fps 26.1.2") :
    // ByteBuffer.allocateDirect(...) était appelé À CHAQUE drawText/drawRect/
    // drawIcon/drawGradientRect — CHAQUE chaîne de texte ET CHAQUE rectangle
    // dessinés CHAQUE FRAME (tout le HUD, même menu fermé) allouaient un
    // NOUVEAU buffer direct (mémoire native hors-tas, PAS un objet Java
    // ordinaire) — pas aussi grave que le bug déjà corrigé plus haut (VBO GPU
    // recréé/détruit à chaque appel, 12 FPS constatés), mais même famille de
    // problème à plus petite échelle : de l'ordre de 10-30+ allocations
    // natives par frame rien que pour le HUD (une par ligne de texte, une par
    // fond de panneau...), strictement inutiles puisque le contenu est
    // entièrement RÉÉCRIT (jamais lu entre deux appels) et la taille needed
    // ne dépasse quasiment jamais celle de l'appel précédent. Un seul buffer
    // direct partagé, agrandi seulement quand nécessaire (jamais réduit,
    // jamais libéré entre deux appels), vidé (clear()) avant chaque
    // réécriture — élimine ces allocations sans changer le contenu écrit.
    static ByteBuffer stagingBuffer;
    static int stagingBufferCapacity;

    static ByteBuffer ensureStagingBuffer(int neededBytes) {
        if (stagingBuffer != null && neededBytes <= stagingBufferCapacity) {
            stagingBuffer.clear();
            return stagingBuffer;
        }
        // Même croissance généreuse (x2 + marge) que ensureVertexBuffer —
        // évite de réallouer à chaque légère variation de longueur de texte.
        int newCapacity = Math.max(4096, Math.max(neededBytes, stagingBufferCapacity * 2));
        stagingBuffer = ByteBuffer.allocateDirect(newCapacity).order(java.nio.ByteOrder.nativeOrder());
        stagingBufferCapacity = newCapacity;
        return stagingBuffer;
    }

    static Object ensureVertexBuffer(Object device, int neededBytes) throws Exception {
        if (vertexBuffer != null && neededBytes <= vertexBufferCapacity) return vertexBuffer;
        if (vertexBuffer != null) {
            try { ((AutoCloseable) vertexBuffer).close(); } catch (Throwable ignored) {}
        }
        // Arrondi généreux (x2 + marge) pour éviter de réallouer à chaque légère
        // variation de longueur de texte — même logique qu'une croissance
        // classique de liste/buffer dynamique (jamais un agrandissement pile-poil).
        long newCapacity = Math.max(4096L, Math.max(neededBytes, vertexBufferCapacity * 2));
        java.util.function.Supplier<String> label = () -> "yuyuframe_text_vbo";
        vertexBuffer = mCreateBufferSized.invoke(device, label, usageBufferVertex | usageBufferCopyDst, newCapacity);
        vertexBufferCapacity = newCapacity;
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: buffer de sommets (re)créé, capacité=" + newCapacity + " octets");
        return vertexBuffer;
    }

    // ── Buffer de projection persistant (mat4x4, 64 octets, jamais recréé) ──
    // Voir commentaire dans resolve() : notre propre matrice orthographique,
    // indépendante de l'état ambiant emprunté par bindDefaultUniforms — évite
    // tout clipping GPU silencieux si le near/far ambiant ne couvre pas z=0.

    static Object projectionBuffer;
    static int projectionVpWidth = -1, projectionVpHeight = -1;

    static Object ensureProjectionBuffer(Object device, Object encoder, int vpWidth, int vpHeight) throws Exception {
        if (projectionBuffer != null && vpWidth == projectionVpWidth && vpHeight == projectionVpHeight) {
            return projectionBuffer;
        }
        if (projectionBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_text_projection";
            projectionBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 64L);
        }
        Object ortho = clsMatrix4f.getConstructor().newInstance();
        // BUG TROUVÉ (utilisateur : glisser le module bouge le texte en
        // miroir, même après synchronisation du timing rects/texte — la
        // vraie cause n'était donc PAS le timing) : drawRoundedRect
        // (Legacy/Modern, voir leurs commentaires "cohérent avec gl_FragCoord
        // et le reste du pipeline (mouse/UI), pas de flip") utilise
        // glOrtho(0,w,0,h,...) — origine BAS-GAUCHE, Y CROISSANT VERS LE HAUT
        // (comme gl_FragCoord/la souris/tout le reste du moteur). Notre
        // projection Blaze3D faisait l'INVERSE (bottom=vpHeight, top=0 — Y
        // croissant vers le BAS) : pour un même "y", rects et texte se
        // positionnaient donc en miroir vertical l'un de l'autre. Fix :
        // bottom=0, top=vpHeight — MÊME convention que le reste du moteur.
        // near=-1000/far=1000 toujours symétrique autour de 0 (évite tout
        // clipping GPU silencieux sur z=0, inchangé).
        mMatrixSetOrtho.invoke(ortho, 0f, (float) vpWidth, 0f, (float) vpHeight, -1000f, 1000f);
        float[] cols = (float[]) mMatrixGetFloatArray.invoke(ortho, (Object) new float[16]);
        ByteBuffer data = ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder());
        for (float v : cols) data.putFloat(v);
        data.flip();
        Object slice = mBufferSlice.invoke(projectionBuffer, 0L, 64L);
        mWriteToBuffer.invoke(encoder, slice, data);
        projectionVpWidth = vpWidth;
        projectionVpHeight = vpHeight;
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: projection orthographique (re)écrite, " + vpWidth + "x" + vpHeight);
        return projectionBuffer;
    }

    static int failureLogCount;

    static volatile String currentStage = "?";

    // ── File d'attente d'un frame (rendu différé) ───────────────────────────
    //
    // BUG TROUVÉ (chaîne API 100% sans exception — texture plate déjà
    // corrigée — texte TOUJOURS invisible) : notre pass Blaze3D dessine dans
    // mc.getFramebuffer().getColorAttachmentView(), la texture INTERMÉDIAIRE
    // que blitToScreen() copie ENSUITE vers le vrai backbuffer de la fenêtre
    // via CommandEncoder.presentTexture() (désassemblage confirmé : le corps
    // de blitToScreen() ne fait QUE ça). Notre hook est à la TAIL de
    // blitToScreen(), donc APRÈS ce présent — on écrit bien dans la texture,
    // mais TROP TARD pour être inclus dans le présent de CETTE frame ; ce
    // contenu est ensuite écrasé par le rendu normal de la frame SUIVANTE
    // avant même d'avoir jamais été présenté. Voir la javadoc de
    // GlobalUiPresentMixin : le dessin GL BRUT (rects/toggles), lui, VEUT
    // être après presentTexture() car celui-ci semble re-binder le FBO par
    // défaut de la fenêtre (FBO 0) — les deux mécanismes ont donc des
    // exigences de timing OPPOSÉES, d'où l'impossibilité de les dessiner
    // au même point.
    //
    // Fix : au lieu de dessiner immédiatement, drawText() EMPILE ses
    // paramètres (queueDraw) — GlobalUiPresentMixin appelle flushQueued() à
    // la HEAD de blitToScreen (donc AVANT presentTexture, alors que
    // getColorAttachmentView() est encore la texture qui VA être présentée
    // CETTE frame). Comme les appels queueDraw() de la frame N ont lieu à la
    // TAIL de blitToScreen (uiDraw), ils ne sont flushés qu'à la HEAD de
    // blitToScreen de la frame N+1 : un retard d'UNE frame (~16 ms à 60
    // FPS), imperceptible pour de l'UI, en échange d'un texte enfin RÉELLEMENT
    // visible. Le dessin GL brut (TAIL, inchangé) reste, lui, synchrone.
    // Empile n'importe quel type de dessin (texte OU rectangle, voir
    // queueRect ci-dessous) dans UN SEUL ordre d'insertion — flushQueued()
    // les exécute dans CET ordre, ce qui garantit le bon z-order (un fond de
    // panneau, empilé avant son texte par HudPanelRenderer.draw(), est donc
    // TOUJOURS dessiné en premier, exactement comme l'ancien pipeline
    // immédiat) sans avoir besoin de deux files séparées.
    interface QueuedDraw { void execute(); }

    static final java.util.List<QueuedDraw> queued = new java.util.ArrayList<>();

    /** Point d'entrée package-private pour Blaze3DText/Blaze3DRect/Blaze3DGradient — la file reste UNIQUE (voir commentaire ci-dessus, garantit l'ordre Z entre types de dessin). */
    static void enqueue(QueuedDraw d) { queued.add(d); }

    /** Appelé depuis {@code GlobalUiPresentMixin} à la HEAD de blitToScreen (avant presentTexture) — dessine tout ce qui a été empilé la frame précédente. */
    public static void flushQueued() {
        if (queued.isEmpty()) return;
        // Copie + clear immédiat : si un dessin relance une exception, on ne
        // rejoue jamais indéfiniment le même lot en boucle.
        QueuedDraw[] batch = queued.toArray(new QueuedDraw[0]);
        queued.clear();
        for (QueuedDraw q : batch) q.execute();
    }

    /** Coins désormais arrondis analytiquement (voir {@code RECT_FRAGMENT_SRC}/{@code GRADIENT_FRAGMENT_SRC}) — un seul type de quad suffit, plus besoin d'une variante à UV variable par coin. */
    static void putSolidQuad(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop, int rgba, short light0, short light1) {
        float u = 0.95f, v = 0.95f;
        putVertexPCTL(buf, xLeft, yTop, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yTop, rgba, u, v, light0, light1);
    }

    /** POSITION(float×3) + COLOR(ubyte×4) + UV0(float×2) + UV2/light(short×2) — 28 octets, ordre EXACT vérifié par désassemblage de VertexFormats.POSITION_COLOR_TEXTURE_LIGHT. */
    static void putVertexPCTL(ByteBuffer buf, float x, float y, int rgba, float u, float v, short light0, short light1) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) (rgba & 0xFF)).put((byte) ((rgba >> 8) & 0xFF)).put((byte) ((rgba >> 16) & 0xFF)).put((byte) ((rgba >> 24) & 0xFF));
        buf.putFloat(u).putFloat(v);
        buf.putShort(light0).putShort(light1);
    }

    static Method mGetFramebuffer;
    static boolean getFramebufferErrorLogged;
    static Object getFramebuffer(Object mc) {
        try {
            if (mGetFramebuffer == null) {
                // 26.1+ : MinecraftClient.getFramebuffer() renommée
                // Minecraft.getMainRenderTarget() (vérifié par javap — voir
                // aussi GlobalUiRenderBridge261, même correspondance déjà
                // établie pour le portage des Mixins).
                String name = MappingsRegistry.isLoaded()
                    ? MappingsRegistry.getObfMethodName("net/minecraft/client/MinecraftClient", "getFramebuffer")
                    : "getMainRenderTarget";
                mGetFramebuffer = mc.getClass().getMethod(name);
            }
            return mGetFramebuffer.invoke(mc);
        } catch (Throwable t) {
            // BUG TROUVÉ (26.1+) : ce catch avalait l'exception SANS LOGGER,
            // retournant null silencieusement — drawText/drawRect/drawIcon
            // font alors juste "if (fb == null) return false;" et abandonnent
            // sans la moindre trace. Explique un dessin qui "démarre" (log de
            // géométrie déjà émis) mais n'atteint jamais la moindre commande
            // GPU, sans exception ni flash visible nulle part.
            if (!getFramebufferErrorLogged) {
                getFramebufferErrorLogged = true;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[LauncherAgent] getFramebuffer: échec (mc.getClass()=" + mc.getClass()
                    + ") : " + t + " | cause réelle : " + cause);
            }
            return null;
        }
    }
}
