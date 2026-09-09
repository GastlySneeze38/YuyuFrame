package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import static com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore.*;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Flou dual-Kawase (roadmap Phase 5.1) — panneau "verre dépoli" : le fond
 * derrière un rect arrondi est flouté par une chaîne de downsample/upsample
 * en plusieurs passes (Marius Bjørge, ARM, SIGGRAPH 2015 — technique déjà
 * largement éprouvée, utilisée par la plupart des mods de blur Minecraft,
 * dont plusieurs déjà présents dans les profils Modrinth de cette machine).
 * Nettement moins cher qu'un vrai flou gaussien en temps réel (chaque passe
 * ne coûte qu'un sample 5-tap ou 8-tap sur une texture de plus en plus
 * petite) tout en restant visuellement très proche.
 *
 * <p>Vérifié par {@code javap} sur {@code GpuTexture.class}/{@code
 * MainTarget.class} du jar client 26.1.2 réel (jamais deviné) : le
 * framebuffer principal du jeu ({@code mc.getFramebuffer().getColorAttachmentView()},
 * déjà utilisé comme CIBLE de rendu par tout le reste de {@code Blaze3DCore})
 * est créé par {@code MainTarget.allocateColorAttachment} avec usage=15
 * (COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT) — {@code
 * USAGE_TEXTURE_BINDING} est bien présent, donc échantillonnable DIRECTEMENT
 * comme {@code Sampler0} en entrée de la 1ère passe de downsample, sans
 * copie préalable dans une texture intermédiaire.
 */
public final class Blaze3DBlur {
    private Blaze3DBlur() {}

    /** Toggle via {@code /yf blurpoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean testEnabled = false;

    /**
     * {@code true} si un vrai panneau de verre est dessinable sur ce bracket —
     * classes Blaze3D présentes ET pipelines de flou effectivement construits.
     * Exposé publiquement (contrairement à {@code isAvailable()}/{@code
     * resolve()}, importés statiquement depuis {@link Blaze3DCore} donc
     * invisibles hors de ce package) pour que {@link
     * com.yuyuframe.launcheragent.apigraphic.UiRenderer#drawGlassPanel} sache
     * s'il doit basculer sur son repli opaque. {@code resolve()} est
     * idempotent/mis en cache ({@code resolveAttempted}), donc appelable à
     * chaque frame sans coût après le premier appel.
     */
    public static boolean isGlassAvailable() {
        return isAvailable() && resolve() && compositePipeline != null;
    }

    /** Panneau de test fixe (centré, 420×260, coins 24/24/4/4 pour vérifier le rayon par coin en même temps, teinte violette 25%) — dessiné en direct (PAS via {@link Blaze3DCore#enqueue}, même style que {@code UiSolidPipelinePoc}, POC autonome). */
    public static void drawTestPanel(int vpWidth, int vpHeight) {
        float w = 420f, h = 260f;
        float x0 = (vpWidth - w) / 2f, y0 = (vpHeight - h) / 2f;
        drawBlurredPanel(x0, y0, x0 + w, y0 + h, 24f, 24f, 4f, 4f, 4,
            new UiColor(0.55f, 0.35f, 0.95f, 1f), 0.25f, vpWidth, vpHeight);
    }

    /**
     * Passthrough Position+UV0 — pas de {@code DynamicTransforms}/{@code
     * ColorModulator} (le flou ne module aucune couleur, juste un
     * échantillonnage texture), volontairement plus minimal que {@link
     * Blaze3DCore#HOME_VERTEX_SRC}. Attributs {@code Color}/{@code UV2}
     * présents dans le {@code VertexFormat} copié (format commun à tout le
     * moteur, voir {@link ShaderPipelineFactory#buildPipeline}) mais non
     * déclarés ici — GLSL ignore silencieusement un attribut de sommet non
     * lu par le shader, même pattern déjà utilisé par {@code
     * GRADIENT_VERTEX_SRC}.
     */
    private static final String BLUR_VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec2 UV0;\n" +
        "out vec2 texCoord0;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * vec4(Position, 1.0);\n" +
        "    texCoord0 = UV0;\n" +
        "}\n";

    /**
     * Filtre de downsample dual-Kawase, 5 échantillons (centre ×4 + 4 coins
     * diagonaux) — {@code u_TexelSize} = taille d'un texel de la texture
     * SOURCE (celle qu'on échantillonne, PAS la destination).
     */
    private static final String BLUR_DOWN_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
        "layout(std140) uniform BlurParams {\n" +
        "    vec4 u_TexelSize;\n" +
        "};\n" +
        "in vec2 texCoord0;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 halfpixel = u_TexelSize.xy * 0.5;\n" +
        "    vec2 uv = texCoord0;\n" +
        "    vec4 sum = texture(Sampler0, uv) * 4.0;\n" +
        "    sum += texture(Sampler0, uv - halfpixel);\n" +
        "    sum += texture(Sampler0, uv + halfpixel);\n" +
        "    sum += texture(Sampler0, uv + vec2(halfpixel.x, -halfpixel.y));\n" +
        "    sum += texture(Sampler0, uv - vec2(halfpixel.x, -halfpixel.y));\n" +
        "    fragColor = sum / 8.0;\n" +
        "}\n";

    /**
     * Filtre d'upsample dual-Kawase, 8 échantillons pondérés (motif "tente")
     * — {@code u_TexelSize} = taille d'un texel de la texture SOURCE (le
     * niveau plus petit qu'on remonte).
     */
    private static final String BLUR_UP_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
        "layout(std140) uniform BlurParams {\n" +
        "    vec4 u_TexelSize;\n" +
        "};\n" +
        "in vec2 texCoord0;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 halfpixel = u_TexelSize.xy * 0.5;\n" +
        "    vec2 uv = texCoord0;\n" +
        "    vec4 sum = texture(Sampler0, uv + vec2(-halfpixel.x * 2.0, 0.0));\n" +
        "    sum += texture(Sampler0, uv + vec2(-halfpixel.x, halfpixel.y)) * 2.0;\n" +
        "    sum += texture(Sampler0, uv + vec2(0.0, halfpixel.y * 2.0));\n" +
        "    sum += texture(Sampler0, uv + vec2(halfpixel.x, halfpixel.y)) * 2.0;\n" +
        "    sum += texture(Sampler0, uv + vec2(halfpixel.x * 2.0, 0.0));\n" +
        "    sum += texture(Sampler0, uv + vec2(halfpixel.x, -halfpixel.y)) * 2.0;\n" +
        "    sum += texture(Sampler0, uv + vec2(0.0, -halfpixel.y * 2.0));\n" +
        "    sum += texture(Sampler0, uv + vec2(-halfpixel.x, -halfpixel.y)) * 2.0;\n" +
        "    fragColor = sum / 12.0;\n" +
        "}\n";

    /**
     * Composite final : même formule SDF boîte-arrondie à rayon par coin que
     * {@link Blaze3DCore#RECT_FRAGMENT_SRC} (dupliquée volontairement — ce
     * pipeline a des uniforms différents, {@code BlurCompositeParams} au lieu
     * d'aucun sampler, pas de raison de complexifier {@code RECT_FRAGMENT_SRC}
     * partagé pour ce seul cas), + échantillonnage du fond flouté (UV =
     * position écran normalisée) teinté par {@code u_TintAndStrength}.
     */
    private static final String BLUR_COMPOSITE_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
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
        "layout(std140) uniform BlurCompositeParams {\n" +
        "    vec4 u_ScreenSize;\n" +
        "    vec4 u_TintAndStrength;\n" +
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
        "    vec2 screenUV = fragPos / u_ScreenSize.xy;\n" +
        "    vec4 backdrop = texture(Sampler0, screenUV);\n" +
        "    vec3 tinted = mix(backdrop.rgb, u_TintAndStrength.rgb, u_TintAndStrength.a);\n" +
        "    vec4 color = vec4(tinted, 1.0) * vertexColor * ColorModulator;\n" +
        "    color.a *= alpha;\n" +
        "    if (color.a < 0.01) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    static Object downPipeline, downShaderSource, upPipeline, upShaderSource, compositePipeline, compositeShaderSource;
    static Object blurParamsBuffer, compositeParamsBuffer;

    static boolean resolveBlurPipeline() {
        try {
            Object downVId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_down.vsh");
            Object downFId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_down.fsh");
            downPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_blur_down", downVId, downFId,
                new String[]{ "Sampler0" }, new String[]{ "Projection", "BlurParams" });
            downShaderSource = ShaderPipelineFactory.shaderSource(downVId, BLUR_VERTEX_SRC, downFId, BLUR_DOWN_FRAGMENT_SRC);

            Object upVId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_up.vsh");
            Object upFId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_up.fsh");
            upPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_blur_up", upVId, upFId,
                new String[]{ "Sampler0" }, new String[]{ "Projection", "BlurParams" });
            upShaderSource = ShaderPipelineFactory.shaderSource(upVId, BLUR_VERTEX_SRC, upFId, BLUR_UP_FRAGMENT_SRC);

            // Vertex identique à RECT_VERTEX_SRC (Position+Color -> fragPos+vertexColor) — même source réutilisée, nouvel identifiant dédié.
            Object compositeVId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_composite.vsh");
            Object compositeFId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_blur_composite.fsh");
            compositePipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_blur_composite", compositeVId, compositeFId,
                new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection", "RectParams", "BlurCompositeParams" });
            compositeShaderSource = ShaderPipelineFactory.shaderSource(compositeVId, RECT_VERTEX_SRC, compositeFId, BLUR_COMPOSITE_FRAGMENT_SRC);

            return downPipeline != null && upPipeline != null && compositePipeline != null;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            LauncherLog.err("[UiRenderer] Blaze3DBlur: résolution pipeline échouée : " + t + " | cause réelle : " + cause);
            return false;
        }
    }

    static Object ensureBlurParamsBuffer(Object device) throws Exception {
        if (blurParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_blur_params";
            blurParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 16L);
        }
        return blurParamsBuffer;
    }

    static Object writeBlurParams(Object device, Object encoder, float texelW, float texelH) throws Exception {
        Object buffer = ensureBlurParamsBuffer(device);
        ByteBuffer data = scratch(16);
        data.putFloat(texelW).putFloat(texelH).putFloat(0f).putFloat(0f);
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 16L);
        mWriteToBuffer.invoke(encoder, slice, data);
        return slice;
    }

    static Object ensureCompositeParamsBuffer(Object device) throws Exception {
        if (compositeParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_blur_composite_params";
            compositeParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 32L);
        }
        return compositeParamsBuffer;
    }

    static Object writeCompositeParams(Object device, Object encoder, float screenW, float screenH,
                                        float tintR, float tintG, float tintB, float tintStrength) throws Exception {
        Object buffer = ensureCompositeParamsBuffer(device);
        ByteBuffer data = scratch(32);
        data.putFloat(screenW).putFloat(screenH).putFloat(0f).putFloat(0f);
        data.putFloat(tintR).putFloat(tintG).putFloat(tintB).putFloat(tintStrength);
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 32L);
        mWriteToBuffer.invoke(encoder, slice, data);
        return slice;
    }

    // ── Chaîne de cibles de rendu hors-écran (downsample) ──────────────────
    //
    // 5 niveaux (÷2 à chaque étage) — suffisant pour un flou "verre dépoli"
    // typique d'UI (pas besoin d'aller plus loin, l'upsample en tente lisse
    // déjà énormément après 3-4 étages). Recréée seulement si le viewport
    // change de taille (resize fenêtre/gui scale), PAS à chaque frame.

    private static final int LEVELS = 5;

    /**
     * Niveau de la pyramide que le composite échantillonne — {@code 1} = quart
     * d'écran.
     *
     * <p>Était implicitement {@code 0} (demi-écran) : la remontée finissait
     * par une passe qui réécrivait un demi-écran entier, de loin la plus chère
     * des sept, pour une différence invisible une fois l'image floutée. Passer
     * à {@code 1} supprime cette passe ET divise par quatre la surface
     * échantillonnée par chaque panneau.
     *
     * <p>Remettre à {@code 0} restaure exactement le rendu d'avant.
     */
    private static final int COMPOSITE_LEVEL = 1;
    private static Object[] levelTexture = new Object[LEVELS];
    private static Object[] levelView = new Object[LEVELS];
    private static int[] levelW = new int[LEVELS], levelH = new int[LEVELS];
    private static int chainVpWidth = -1, chainVpHeight = -1;
    private static Object linearSampler;

    private static void ensureChain(Object device, int vpWidth, int vpHeight) throws Exception {
        if (chainVpWidth == vpWidth && chainVpHeight == vpHeight && levelTexture[0] != null) return;
        for (int i = 0; i < LEVELS; i++) {
            if (levelTexture[i] != null) {
                try { mCloseTexture.invoke(levelTexture[i]); } catch (Throwable ignored) {}
                levelTexture[i] = null;
                levelView[i] = null;
            }
        }
        int w = vpWidth, h = vpHeight;
        int usage = usageTextureBinding | usageTextureRenderAttachment;
        for (int i = 0; i < LEVELS; i++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            levelW[i] = w;
            levelH[i] = h;
            final int idx = i;
            java.util.function.Supplier<String> label = () -> "yuyuframe_blur_level_" + idx;
            levelTexture[i] = mCreateTexture.invoke(device, label, usage, fieldTextureFormatRgba8, w, h, 1, 1);
            levelView[i] = mCreateTextureView.invoke(device, levelTexture[i]);
        }
        if (linearSampler == null) {
            linearSampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        }
        chainVpWidth = vpWidth;
        chainVpHeight = vpHeight;
        LauncherLog.ui(1, "[UiRenderer] Blaze3DBlur: chaîne de flou (re)créée, " + vpWidth + "x" + vpHeight + " -> " + LEVELS + " niveaux");
    }

    /** Dessine un quad plein écran-destination échantillonnant {@code srcView} avec le pipeline {@code pipeline}, écrit dans {@code dstView}. */
    private static void drawBlurPass(Object device, Object encoder, Object pipeline, Object shaderSource,
                                      Object srcView, Object srcSampler, int srcW, int srcH,
                                      Object dstView, int dstW, int dstH, String debugLabel) throws Exception {
        currentStage = "drawBlurPass(" + debugLabel + ")/vertices";
        ByteBuffer verts = ensureStagingBuffer(4 * 28);
        int rgba = 0xFFFFFFFF;
        short light0 = 0, light1 = 0;
        putVertexPCTL(verts, 0f, (float) dstH, rgba, 0f, 1f, light0, light1);
        putVertexPCTL(verts, 0f, 0f, rgba, 0f, 0f, light0, light1);
        putVertexPCTL(verts, (float) dstW, 0f, rgba, 1f, 0f, light0, light1);
        putVertexPCTL(verts, (float) dstW, (float) dstH, rgba, 1f, 1f, light0, light1);
        verts.flip();

        currentStage = "drawBlurPass(" + debugLabel + ")/vertexBuffer";
        Object vbo = ensureVertexBuffer(device, verts.remaining());
        Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
        mWriteToBuffer.invoke(encoder, slice, verts);

        currentStage = "drawBlurPass(" + debugLabel + ")/projection";
        Object projectionBuf = ensureProjectionBuffer(device, encoder, dstW, dstH);
        Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

        currentStage = "drawBlurPass(" + debugLabel + ")/params";
        Object blurParamsSlice = writeBlurParams(device, encoder, 1f / srcW, 1f / srcH);

        currentStage = "drawBlurPass(" + debugLabel + ")/renderPass";
        java.util.function.Supplier<String> passLabel = () -> "yuyuframe_blur_" + debugLabel;
        Object pass = mCreateRenderPass.invoke(encoder, passLabel, dstView, OptionalInt.empty());
        try {
            ShaderPipelineFactory.precompile(device, pipeline, shaderSource);
            mSetPipeline.invoke(pass, pipeline);
            if (mDisableScissor != null) mDisableScissor.invoke(pass);
            mBindDefaultUniforms.invoke(null, pass);
            mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
            mSetUniformSlice.invoke(pass, "BlurParams", blurParamsSlice);
            mBindTexture.invoke(pass, "Sampler0", srcView, srcSampler);
            mSetVertexBuffer.invoke(pass, 0, vbo);

            if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
            Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
            Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
            mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
            mDrawIndexed.invoke(pass, 0, 0, 6, 1);
        } finally {
            mClosePass.invoke(pass);
        }
    }

    /**
     * Calcule la chaîne dual-Kawase complète pour le frame courant — à
     * appeler UNE FOIS avant de composer un ou plusieurs panneaux "verre
     * dépoli" (le résultat, {@code levelView[COMPOSITE_LEVEL]}, reste valide tant qu'aucun
     * autre appel à cette méthode n'a lieu dans le même frame). {@code
     * passes} borné à {@code [1, LEVELS]}.
     */
    private static boolean renderBlurChain(int vpWidth, int vpHeight, int passes) {
        try {
            currentStage = "renderBlurChain/device";
            Object device = mGetDevice.invoke(null);
            currentStage = "renderBlurChain/encoder";
            Object encoder = mCreateCommandEncoder.invoke(device);
            currentStage = "renderBlurChain/ensureChain";
            ensureChain(device, vpWidth, vpHeight);

            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            Object sourceView = mGetColorAttachmentView.invoke(fb);
            if (sourceView == null) return false;

            int p = Math.max(1, Math.min(passes, LEVELS));

            Object curView = sourceView;
            int curW = vpWidth, curH = vpHeight;
            for (int i = 0; i < p; i++) {
                drawBlurPass(device, encoder, downPipeline, downShaderSource,
                    curView, linearSampler, curW, curH, levelView[i], levelW[i], levelH[i], "down" + i);
                curView = levelView[i];
                curW = levelW[i];
                curH = levelH[i];
            }
            // La remontée s'arrête à COMPOSITE_LEVEL et ne redescend PLUS
            // jusqu'au niveau 0 : c'était la passe la plus chère de toute la
            // chaîne (un demi-écran écrit, avec 8 prélèvements par pixel) pour
            // un gain nul — le composite rééchantillonne de toute façon en
            // bilinéaire, et sur une image aussi floue la différence entre un
            // quart et un demi d'écran ne se voit pas.
            for (int i = p - 1; i > COMPOSITE_LEVEL; i--) {
                drawBlurPass(device, encoder, upPipeline, upShaderSource,
                    levelView[i], linearSampler, levelW[i], levelH[i], levelView[i - 1], levelW[i - 1], levelH[i - 1], "up" + i);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] Blaze3DBlur.renderBlurChain a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    /**
     * Empile un panneau "verre dépoli" — fond = backdrop courant flouté
     * (dual-Kawase, {@code passes} étages) teinté par {@code tint}/{@code
     * tintStrength}, coins arrondis PAR COIN (même convention que {@link
     * Blaze3DRect#queueRect}). Recalcule la chaîne de flou À CHAQUE appel
     * (le contenu derrière le panneau peut changer d'une frame à l'autre) —
     * si plusieurs panneaux floutés se chevauchent dans le même frame,
     * chacun voit le vrai backdrop courant au moment de son dessin (ordre de
     * la file {@link Blaze3DCore#enqueue}, cohérent avec le reste du moteur).
     *
     * @param passes        nombre d'étages downsample/upsample, {@code [1,5]} — plus haut = flou plus fort ET plus coûteux (chaque étage = 1 passe de rendu supplémentaire).
     * @param tint          couleur de teinte mélangée par-dessus le flou (look "verre coloré") ; alpha ignoré, voir {@code tintStrength}.
     * @param tintStrength  {@code [0,1]} — 0 = flou pur (aucune teinte), 1 = couleur plate (flou invisible).
     */
    public static void queueBlurredPanel(float x0, float y0, float x1, float y1,
                                          float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                          int passes, UiColor tint, float tintStrength, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawBlurredPanel(x0, y0, x1, y1, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            passes, tint, tintStrength, vpWidth, vpHeight));
    }

    /**
     * Chaîne de flou PARTAGÉE par tout un frame — à empiler UNE SEULE FOIS,
     * AVANT tous les {@link #queueGlassPanel} qui la consommeront (rework UI
     * "verre dépoli à la Apple", 2026-08-27).
     *
     * <p>POURQUOI CETTE SÉPARATION (limite RÉELLE de {@link #queueBlurredPanel},
     * mesurée sur le papier avant d'écrire la moindre ligne d'écran) : cette
     * dernière recalcule TOUTE la chaîne dual-Kawase à chaque appel, soit
     * jusqu'à 5 passes de downsample + 4 d'upsample = <b>9 passes de rendu
     * plein écran par panneau</b>. Acceptable pour UN panneau isolé (l'usage
     * pour lequel elle avait été écrite en Phase 5.1), catastrophique dès
     * qu'une interface en pose beaucoup : une sidebar + une barre de recherche
     * + 15 cartes de mods = ~17 panneaux × 9 = <b>150+ passes plein écran par
     * frame</b>, pour un résultat visuel identique puisqu'ils échantillonnent
     * TOUS le même arrière-plan.
     *
     * <p>Ce découplage ramène ça à <b>9 passes pour tout le frame</b>, quel que
     * soit le nombre de panneaux — exactement le modèle des vraies UI "vibrancy"
     * (macOS/iOS) : un seul backdrop flouté calculé une fois, échantillonné par
     * autant de surfaces de verre que voulu.
     *
     * <p>CONSÉQUENCE VISUELLE ASSUMÉE (et souhaitable ici) : puisque la chaîne
     * est calculée AVANT que le moindre élément d'UI ne soit dessiné (elle est
     * en tête de la file {@link Blaze3DCore#enqueue}, exécutée à
     * {@code flushQueued}), chaque panneau floute <b>le monde du jeu</b>, jamais
     * l'UI dessinée avant lui. Deux panneaux de verre qui se chevauchent ne se
     * floutent donc pas l'un l'autre — c'est le comportement voulu (et celui de
     * macOS), pas une limite : du verre qui floute du verre vire vite à la
     * bouillie opaque. Pour un vrai empilement de flous (une modale qui doit
     * flouter l'écran de verre derrière elle), utiliser {@link
     * #queueBlurredPanel} pour CE panneau-là : il recalcule la chaîne à son
     * propre tour dans la file, donc voit bien tout ce qui a été dessiné avant.
     */
    public static void queueFrameChain(int passes, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> renderFrameChain(passes, vpWidth, vpHeight));
    }

    /**
     * Recalcule la chaîne une frame sur N. {@code 1} = à chaque frame
     * (comportement d'origine).
     *
     * <p>OPTIMISATION (2026-08-31, « le flou prend énormément de perf ») : la
     * chaîne coûtait 7 passes par frame sur les écrans (4 descentes + 3
     * remontées), la première écrivant un demi-écran entier. Or un fond flouté
     * est une image BASSE FRÉQUENCE : la recalculer 60 fois par seconde
     * n'apporte rien de visible. Une frame sur deux divise le coût par deux,
     * et le décalage n'est perceptible que si le décor change brutalement —
     * jamais le cas derrière un menu, et invisible derrière un petit panneau
     * de HUD.
     */
    public static int RECOMPUTE_INTERVAL = 2;

    private static int frameCounter;

    private static boolean renderFrameChain(int passes, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        // Réutilisation temporelle. Un changement de viewport force le recalcul :
        // les textures sont recréées, celles d'avant ne veulent plus rien dire.
        boolean viewportChanged = (vpWidth != chainVpWidth || vpHeight != chainVpHeight);
        if (!viewportChanged && levelTexture[0] != null && RECOMPUTE_INTERVAL > 1) {
            if ((++frameCounter % RECOMPUTE_INTERVAL) != 0) return true;
        }
        return renderBlurChain(vpWidth, vpHeight, passes);
    }

    /**
     * Calcule la chaîne IMMÉDIATEMENT, sans passer par la file — pour la voie
     * « état de GUI vanilla » (2026-08-31).
     *
     * <p>Sur cette voie, ce n'est plus nous qui dessinons : vanilla soumet nos
     * panneaux pendant {@code GuiRenderer.render}, donc AVANT que la file
     * Blaze3D ne soit vidée. Une chaîne mise en file arriverait trop tard — les
     * panneaux échantillonneraient le flou de la frame précédente, ou rien du
     * tout à la première.
     *
     * <p>Appelée depuis le hook d'extraction : la scène y est déjà rendue dans
     * le framebuffer principal, la source du flou est donc valide.
     */
    public static boolean renderChainNow(int passes, int vpWidth, int vpHeight) {
        return renderFrameChain(passes, vpWidth, vpHeight);
    }

    /**
     * Vue sur le résultat de la chaîne ({@code levelView[COMPOSITE_LEVEL]}, quart de
     * résolution) — c'est elle que le composite échantillonne, et donc ce que
     * doit référencer un {@code TextureSetup} côté état de GUI vanilla.
     *
     * <p>{@code null} tant qu'aucune chaîne n'a été calculée.
     */
    public static Object blurredView() {
        return levelView[COMPOSITE_LEVEL];
    }

    /** Échantillonneur linéaire utilisé par le composite — même filtrage des deux côtés. */
    public static Object blurSampler() {
        return linearSampler;
    }

    /**
     * Panneau de verre qui RÉUTILISE la chaîne déjà calculée par {@link
     * #queueFrameChain} — aucun recalcul, juste la passe de composite (1 draw).
     * Voir {@link #queueFrameChain} pour le pourquoi et les paramètres
     * {@code tint}/{@code tintStrength}.
     *
     * <p>Filet de sécurité : si aucune chaîne n'a été calculée pour ce frame
     * (appelant qui a oublié {@code queueFrameChain}, ou tout premier frame),
     * le composite en calcule une lui-même plutôt que d'échantillonner une
     * texture nulle — donc jamais de panneau invisible/noir, au pire le coût
     * de l'ancien comportement.
     */
    public static void queueGlassPanel(float x0, float y0, float x1, float y1,
                                        float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                        UiColor tint, float tintStrength, float opacity, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawGlassComposite(x0, y0, x1, y1, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            tint, tintStrength, opacity, vpWidth, vpHeight));
    }

    /** Nombre d'étages par défaut quand un composite doit se rabattre sur son propre calcul de chaîne (voir {@link #queueGlassPanel}). */
    private static final int DEFAULT_PASSES = 4;

    private static boolean drawBlurredPanel(float x0, float y0, float x1, float y1,
                                             float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                             int passes, UiColor tint, float tintStrength, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        currentStage = "drawBlurredPanel/renderBlurChain";
        if (!renderBlurChain(vpWidth, vpHeight, passes)) return false;
        return drawGlassComposite(x0, y0, x1, y1, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            tint, tintStrength, 1f, vpWidth, vpHeight);
    }

    /**
     * @param opacity {@code [0,1]} — opacité du panneau ENTIER (pas la force
     *        de teinte, voir {@code tintStrength}) : appliquée via la couleur
     *        de sommet, donc {@code 0.5} laisse voir ce qui a été dessiné
     *        SOUS le panneau à travers le verre. Indispensable pour un
     *        panneau qui s'estompe (carte en bord de zone défilante, voir
     *        {@code UiWidget#clipFade}, ou apparition en cascade {@code
     *        UiStagger}) : sans lui, le composite écrit toujours alpha=1 et
     *        une carte en train de disparaître resterait pleinement opaque
     *        jusqu'à sa suppression, d'un coup sec.
     */
    private static boolean drawGlassComposite(float x0, float y0, float x1, float y1,
                                               float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                               UiColor tint, float tintStrength, float opacity, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            // Chaîne absente (queueFrameChain jamais appelée ce frame, ou
            // viewport recréé entre-temps) — voir javadoc de queueGlassPanel.
            if (levelView[COMPOSITE_LEVEL] == null && !renderBlurChain(vpWidth, vpHeight, DEFAULT_PASSES)) return false;

            currentStage = "minecraftClient(blurpanel)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(blurpanel)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(blurpanel)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float maxR = Math.min((x1 - x0) / 2f, (y1 - y0) / 2f);
            float rTL = Math.max(0f, Math.min(radiusTopLeft, maxR));
            float rTR = Math.max(0f, Math.min(radiusTopRight, maxR));
            float rBL = Math.max(0f, Math.min(radiusBottomLeft, maxR));
            float rBR = Math.max(0f, Math.min(radiusBottomRight, maxR));

            currentStage = "getDevice(blurpanel)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(blurpanel)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            // Blanc × opacité — même empaquetage ABGR que Blaze3DRect
            // (a<<24 | b<<16 | g<<8 | r) ; seul l'alpha varie, la couleur du
            // verre venant du flou + de la teinte dans le fragment shader.
            int alphaByte = Math.max(0, Math.min(255, Math.round(opacity * 255f)));
            int rgba = (alphaByte << 24) | 0x00FFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(blurpanel)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(blurpanel)";
            Object identity4 = identityMatrix4f();
            Object colorMod = ctorVector4f.newInstance(1f, 1f, 1f, 1f);
            Object zero3 = zeroVector3f();
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, colorMod, zero3, identity4);

            currentStage = "ensureProjectionBuffer(blurpanel)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "writeRectParams(blurpanel)";
            Object rectParamsSlice = writeRectParams(device, encoder, x0, y0, x1, y1, rTL, rTR, rBL, rBR);

            currentStage = "writeCompositeParams(blurpanel)";
            Object compositeParamsSlice = writeCompositeParams(device, encoder, (float) vpWidth, (float) vpHeight,
                tint.r, tint.g, tint.b, tintStrength);

            currentStage = "createRenderPass(blurpanel)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_blurpanel";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(blurpanel)";
                ShaderPipelineFactory.precompile(device, compositePipeline, compositeShaderSource);
                mSetPipeline.invoke(pass, compositePipeline);
                if (mDisableScissor != null) mDisableScissor.invoke(pass);
                mBindDefaultUniforms.invoke(null, pass);
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                mSetUniformSlice.invoke(pass, "RectParams", rectParamsSlice);
                mSetUniformSlice.invoke(pass, "BlurCompositeParams", compositeParamsSlice);
                // Résultat final de la chaîne = levelView[0] (demi-résolution du
                // dernier niveau upsamplé) — pas de passe supplémentaire pour
                // remonter à la résolution native : l'échantillonnage linéaire
                // au composite lisse déjà cet écart, gain négligeable pour un
                // coût de passe en plus (même compromis que la plupart des
                // implémentations dual-Kawase de blur Minecraft référencées).
                mBindTexture.invoke(pass, "Sampler0", levelView[COMPOSITE_LEVEL], linearSampler);
                currentStage = "setVertexBuffer(blurpanel)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                mDrawIndexed.invoke(pass, 0, 0, 6, 1);
            } finally {
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] Blaze3DBlur.drawGlassComposite a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }
}
