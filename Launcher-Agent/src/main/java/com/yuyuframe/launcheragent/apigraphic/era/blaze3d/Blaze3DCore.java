package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Infrastructure partagée du rendu Blaze3D era E (26.1.2/1.21.6+) — pipelines
 * "maison" ({@code HOME_VERTEX_SRC}/{@code RECT_*}/{@code RECT_BATCH_*}),
 * helpers de géométrie (quads, tampons persistants, projection orthographique)
 * et la file de dessin différé PARTAGÉE ({@link #enqueue}/{@link #flushQueued()})
 * qui garantit l'ordre Z entre {@link Blaze3DText}/{@link Blaze3DRect}/{@link
 * Blaze3DGradient} — scindé depuis l'ancien {@code UiTextBlaze3D.java} (2208
 * lignes, tout dans un seul fichier), même granularité que {@code GlBridge}
 * pour les backends GL legacy/moderne.
 *
 * <p>PLUS AUCUNE RÉFLEXION, des DEUX côtés depuis la v1106 (étape 3 close) :
 * tous les appels GPU passent par {@link Blaze3DGpu}, dont l'implémentation est
 * celle de la version en cours ({@link Blaze3DGpus#active()}) — {@code
 * Blaze3DGpu1211} et {@code Blaze3DGpu261}, toutes deux typées. Ce fichier ne
 * connaît donc plus aucun nom de classe du jeu : il ne manipule que des
 * poignées opaques rendues par l'interface.
 */
public final class Blaze3DCore {
    private Blaze3DCore() {}

    static Boolean available;

    /** {@code true} seulement si les classes Blaze3D (GpuDevice etc.) existent sur ce bracket — sinon, laisser {@link UiRenderer} retomber sur son pipeline SDF existant. */
    public static boolean isAvailable() {
        // L'ère est REÇUE (VersionProfile.renderEra) — plus de sondage de
        // GpuDevice ici. Le sondage existe toujours, mais UNE fois, et dans
        // apimixin, dont c'est le métier : voir RenderEra et
        // VersionProfileRegistry.activeRenderEra().
        if (available == null) {
            available = com.yuyuframe.launcheragent.apigraphic.backend.RenderEra.active()
                == com.yuyuframe.launcheragent.apigraphic.backend.RenderEra.BLAZE3D;
        }
        return available;
    }

    // ── Implémentation GPU de la version en cours ───────────────────────────

    /**
     * Posée par {@link #resolve()} (jamais nulle une fois {@code resolveOk}) et
     * partagée par import statique avec les satellites {@link Blaze3DRect}/
     * {@link Blaze3DText}/{@link Blaze3DGradient}/{@link Blaze3DBlur}/{@link
     * Blaze3DBlend} — leur unique accès au GPU.
     */
    static Blaze3DGpu gpu;

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
            batchParamsBuffer = gpu.createBuffer(device, "yuyuframe_rect_batch_params",
                gpu.usageBufferUniform() | gpu.usageBufferCopyDst(), 16L);
        }
        return batchParamsBuffer;
    }

    static Object writeBatchParams(Object device, Object encoder, float radius) throws Exception {
        Object buffer = ensureBatchParamsBuffer(device);
        ByteBuffer data = scratch(16);
        data.putFloat(radius).putFloat(0f).putFloat(0f).putFloat(0f);
        data.flip();
        Object slice = gpu.slice(buffer, 0L, 16L);
        gpu.write(encoder, slice, data);
        return slice;
    }

    static Object ensureRectParamsBuffer(Object device) throws Exception {
        if (rectParamsBuffer == null) {
            rectParamsBuffer = gpu.createBuffer(device, "yuyuframe_rect_params",
                gpu.usageBufferUniform() | gpu.usageBufferCopyDst(), 32L);
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
        ByteBuffer data = scratch(32);
        data.putFloat(x0).putFloat(y0).putFloat(x1).putFloat(y1);
        data.putFloat(radiusTopLeft).putFloat(radiusTopRight).putFloat(radiusBottomLeft).putFloat(radiusBottomRight);
        data.flip();
        Object slice = gpu.slice(buffer, 0L, 32L);
        gpu.write(encoder, slice, data);
        return slice;
    }

    /**
     * Récupère l'implémentation GPU de la version et construit les pipelines
     * maison. Idempotent (mis en cache) — appelé au début de chaque dessin.
     */
    static synchronized boolean resolve() {
        if (resolveAttempted) return resolveOk;
        resolveAttempted = true;
        try {
            gpu = Blaze3DGpus.active();
            if (gpu == null) {
                throw new IllegalStateException("aucune implémentation Blaze3DGpu pour cette version");
            }

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
            Object vertexId = gpu.identifier("yuyuframe", "shader/ui_blaze3d.vsh");
            Object fragmentId = gpu.identifier("yuyuframe", "shader/ui_blaze3d.fsh");
            homePipeline = gpu.buildPipeline("ui_blaze3d", vertexId, fragmentId,
                new String[]{ "Sampler0", "Sampler2" }, new String[]{ "DynamicTransforms", "Projection" }, null, null);
            homeShaderSource = gpu.shaderSource(vertexId, HOME_VERTEX_SRC, fragmentId, HOME_FRAGMENT_SRC);

            // Pipeline rect/dégradé-simple à coins arrondis analytiques (voir
            // RECT_VERTEX_SRC/RECT_FRAGMENT_SRC) — remplace homePipeline pour
            // drawRect/drawGradientRect/drawGradientRect2D. Aucun sampler
            // (pas de masque-texture, tout est calculé par pixel).
            Object rectVertexId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_rect.vsh");
            Object rectFragmentId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_rect.fsh");
            rectPipeline = gpu.buildPipeline("ui_blaze3d_rect", rectVertexId, rectFragmentId,
                new String[0], new String[]{ "DynamicTransforms", "Projection", "RectParams" }, null, null);
            rectShaderSource = gpu.shaderSource(rectVertexId, RECT_VERTEX_SRC, rectFragmentId, RECT_FRAGMENT_SRC);

            // Pipeline rects BATCHÉS (voir RECT_BATCH_VERTEX_SRC/RECT_BATCH_FRAGMENT_SRC) — roadmap Phase 5.5.
            Object batchVertexId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_rect_batch.vsh");
            Object batchFragmentId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_rect_batch.fsh");
            batchPipeline = gpu.buildPipeline("ui_blaze3d_rect_batch", batchVertexId, batchFragmentId,
                new String[0], new String[]{ "DynamicTransforms", "Projection", "BatchParams" }, null, null);
            batchShaderSource = gpu.shaderSource(batchVertexId, RECT_BATCH_VERTEX_SRC, batchFragmentId, RECT_BATCH_FRAGMENT_SRC);

            // Pipeline dégradé multi-stop / texte SDF — construits par
            // Blaze3DGradient/Blaze3DText eux-mêmes (le fichier qui possède
            // le GLSL possède aussi le code qui le compile), on vérifie
            // juste ici que ça a réussi.
            boolean gradientPipelineOk = Blaze3DGradient.resolveGradientPipeline();
            boolean textPipelineOk = Blaze3DText.resolveTextPipeline();
            boolean blurPipelineOk = Blaze3DBlur.resolveBlurPipeline();
            boolean blendPipelineOk = Blaze3DBlend.resolveBlendPipeline();

            if (homePipeline == null || rectPipeline == null || batchPipeline == null
                    || !gradientPipelineOk || !textPipelineOk || !blurPipelineOk || !blendPipelineOk) {
                throw new IllegalStateException("un pipeline maison n'a pas pu être construit (voir logs)");
            }

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
        Object nativeImage = gpu.newRgbaImage(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                gpu.setPixelAbgr(nativeImage, x, y, nativeColor);
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
        Object nativeImage = gpu.newRgbaImage(1, 1);
        gpu.setPixelAbgr(nativeImage, 0, 0, 0xFFFFFFFF); // petit-boutiste RGBA — blanc opaque quel que soit l'ordre des octets
        Object device = gpu.device();
        Object texture = gpu.createTexture(device, "yuyuframe_text_white1x1",
            gpu.usageTextureBinding() | gpu.usageTextureCopyDst(), 1, 1, 1);
        Object encoder = gpu.encoder(device);
        gpu.uploadImage(encoder, texture, nativeImage);
        Object textureView = gpu.createTextureView(device, texture);
        whiteTexture = new Object[]{texture, textureView, gpu.linearSampler()};
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

    /**
     * Tampons natifs RÉUTILISÉS pour les écritures d'uniformes (RectParams,
     * BlurParams, projection...) — un par TAILLE demandée.
     *
     * <p>AUDIT PERF : ces écritures faisaient un {@code
     * ByteBuffer.allocateDirect(...)} À CHAQUE DRAW. Une allocation directe
     * n'est pas un simple {@code new} : elle passe par un {@code malloc} natif
     * et enregistre un {@code Cleaner} auprès du GC, pour 16 à 64 octets, des
     * dizaines de fois par frame. Le tampon de SOMMETS avait déjà été corrigé
     * ainsi (voir {@link #ensureStagingBuffer}) — les tampons d'UNIFORMES
     * avaient été oubliés.
     *
     * <p>Indexé par taille : ces écritures sont toutes séquentielles dans une
     * même passe (remplir puis envoyer, jamais deux en vol simultanément),
     * donc un tampon par taille suffit — pas besoin d'un pool.
     */
    private static final java.util.Map<Integer, ByteBuffer> SCRATCH = new java.util.HashMap<>();

    static ByteBuffer scratch(int bytes) {
        ByteBuffer b = SCRATCH.get(bytes);
        if (b == null) {
            b = ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
            SCRATCH.put(bytes, b);
        }
        b.clear();
        return b;
    }

    // Matrice identité et vecteur nul PARTAGÉS (modelView/offset de
    // DynamicTransforms, constants) : désormais détenus par l'implémentation
    // Blaze3DGpu, derrière dynamicTransforms(r,g,b,a) — plus rien à
    // reconstruire ici à chaque draw.

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
            // GpuBuffer est AutoCloseable — simple transtypage, pas de réflexion.
            // Un échec ici ne doit pas empêcher la recréation du tampon, mais
            // il est journalisé (jamais avalé en silence).
            try {
                ((AutoCloseable) vertexBuffer).close();
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] UiTextBlaze3D: fermeture de l'ancien buffer de sommets échouée : " + t);
            }
        }
        // Arrondi généreux (x2 + marge) pour éviter de réallouer à chaque légère
        // variation de longueur de texte — même logique qu'une croissance
        // classique de liste/buffer dynamique (jamais un agrandissement pile-poil).
        long newCapacity = Math.max(4096L, Math.max(neededBytes, vertexBufferCapacity * 2));
        vertexBuffer = gpu.createBuffer(device, "yuyuframe_text_vbo",
            gpu.usageBufferVertex() | gpu.usageBufferCopyDst(), newCapacity);
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
            projectionBuffer = gpu.createBuffer(device, "yuyuframe_text_projection",
                gpu.usageBufferUniform() | gpu.usageBufferCopyDst(), 64L);
        }
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
        //
        // Écrite À LA MAIN (plus de JOML Matrix4f.setOrtho par réflexion) —
        // même matrice, colonne par colonne, ordre de Matrix4f.get(float[])
        // (column-major, ce qu'attend un mat4 std140) :
        //   m00 = 2/(right-left), m11 = 2/(top-bottom), m22 = 2/(near-far),
        //   m30 = (right+left)/(left-right) = -1, m31 = -1,
        //   m32 = (far+near)/(near-far) = 0 (near/far symétriques), m33 = 1.
        ByteBuffer data = scratch(64);
        data.putFloat(2f / vpWidth).putFloat(0f).putFloat(0f).putFloat(0f);
        data.putFloat(0f).putFloat(2f / vpHeight).putFloat(0f).putFloat(0f);
        data.putFloat(0f).putFloat(0f).putFloat(2f / (-1000f - 1000f)).putFloat(0f);
        data.putFloat(-1f).putFloat(-1f).putFloat(0f).putFloat(1f);
        data.flip();
        Object slice = gpu.slice(projectionBuffer, 0L, 64L);
        gpu.write(encoder, slice, data);
        projectionVpWidth = vpWidth;
        projectionVpHeight = vpHeight;
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: projection orthographique (re)écrite, " + vpWidth + "x" + vpHeight);
        return projectionBuffer;
    }

    static int failureLogCount;

    /**
     * Étape courante, pour situer une exception dans les logs.
     *
     * <p>NON volatile (AUDIT PERF) : il y en a ~180 écritures par frame
     * (69 rien que dans Blaze3DRect), et chaque écriture volatile est une
     * barrière mémoire. C'est un champ de DIAGNOSTIC, lu uniquement depuis le
     * même thread de rendu qui l'écrit, dans un {@code catch} — la visibilité
     * inter-threads qu'apportait {@code volatile} n'a jamais servi à rien ici.
     */
    static String currentStage = "?";

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

    /**
     * Nombre de dessins empilés pour la frame EN COURS — vidée exactement une
     * fois par frame par {@link #flushQueued()}, donc une file vide signifie
     * "rien n'a encore été dessiné cette frame".
     *
     * <p>Exposé pour {@code HudPanelRenderer.ensureGlassChain} : le HUD n'a
     * pas de point d'entrée unique par frame où empiler sa chaîne de flou
     * partagée (plusieurs chemins de rendu selon qu'un écran vanilla est
     * ouvert ou non), et ce moteur n'expose aucun compteur de frames — c'est
     * l'indicateur "début de frame" le moins coûteux disponible.
     */
    public static int queuedCount() { return queued.size(); }

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

    // La cible de rendu principale (ex-getFramebuffer + getColorAttachmentView
    // par réflexion) est rendue directement par gpu.mainColorView().
}
