package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;

import static com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Rects/coins arrondis + icônes RGBA era E (Blaze3D) — même pipeline "maison"
 * que le texte ({@link Blaze3DCore#HOME_VERTEX_SRC}, masque de coin via
 * texture). Scindé depuis l'ancien {@code UiTextBlaze3D.java} — voir
 * {@link Blaze3DCore} pour l'infra partagée.
 */
public final class Blaze3DRect {
    private Blaze3DRect() {}

    // ── Atlas partagé d'icônes arbitraires (pastilles de mod/pack) — roadmap
    // Phase 5.5 (batching/atlas). REMPLACE l'ancienne texture GPU dédiée par
    // icône (une allocation + un bind Sampler0 différent par icône, ~15-20
    // icônes visibles sur un écran comme UiMainMenuScreen) : désormais UNE
    // seule texture GPU 2048×2048 partagée, chaque icône occupe un sous-rect
    // — même bénéfice mémoire/changements d'état que UiFont (police), jamais
    // appliqué aux icônes jusqu'ici. Packing "shelf" (même principe que
    // UiFont#UiFont, généralisé pour des hauteurs variables — les icônes,
    // contrairement aux glyphes, n'ont pas une cellHeight commune) : avance
    // en X sur l'étagère courante, saute à l'étagère suivante (hauteur = max
    // des icônes déjà posées dessus) quand ça déborde en largeur.
    //
    // NE remplace PAS le batching des DRAW CALLS eux-mêmes (chaque drawIcon
    // reste un createRenderPass/drawIndexed séparé) — voir la javadoc de
    // #drawIcon : regrouper les appels interLEAVÉS avec d'autres types de
    // dessin (rect/texte) casserait l'ordre Z durement acquis de ce moteur
    // (voir l'historique de bugs de composition dans Blaze3DCore/
    // UiScrollContainer), écarté pour cette raison — l'atlas seul réduit déjà
    // les changements de texture bindés, la vraie fuite mémoire/état visée
    // par cet item de roadmap.

    private static final int ATLAS_SIZE = 2048;
    private static final int ATLAS_PADDING = 2; // évite le bleeding bilinéaire entre deux icônes adjacentes sur l'étagère

    private static Object atlasTexture, atlasView, atlasSampler;
    private static int shelfX = ATLAS_PADDING, shelfY = ATLAS_PADDING, shelfRowH = 0;
    private static final Map<String, float[]> ICON_UV = new HashMap<>(); // cacheKey -> [u0,v0,u1,v1]

    private static void ensureAtlasTexture(Object device) throws Exception {
        if (atlasTexture != null) return;
        java.util.function.Supplier<String> label = () -> "yuyuframe_icon_atlas";
        atlasTexture = mCreateTexture.invoke(device, label, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, ATLAS_SIZE, ATLAS_SIZE, 1, 1);
        atlasView = mCreateTextureView.invoke(device, atlasTexture);
        atlasSampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: atlas d'icônes créé (" + ATLAS_SIZE + "x" + ATLAS_SIZE + ")");
    }

    /**
     * Entrée d'atlas pour l'ÉTAT DE GUI de vanilla — ouvre au reste du moteur
     * ce que {@link #ensureIconInAtlas} garde pour la file Blaze3D.
     *
     * <p>Ajouté le 2026-08-31 avec {@code Blaze3DGuiIcon} : jusque-là, une
     * icône ne pouvait partir que par la file, donc APRÈS la présentation,
     * donc par-dessus le chat. Le même atlas sert désormais les deux chemins —
     * une icône déjà packée pour l'un est immédiatement disponible pour
     * l'autre, sans seconde copie GPU.
     *
     * <p>À appeler sur le thread de rendu, device disponible (c'est le cas
     * pendant la passe d'extraction de la GUI).
     *
     * @return {@code [float[]{u0,v0,u1,v1}, GpuTextureView, GpuSampler]}, ou
     *         {@code null} si Blaze3D est indisponible ou l'atlas plein.
     */
    public static Object[] guiAtlasEntry(String cacheKey, BufferedImage img) {
        if (!isAvailable() || !resolve()) return null;
        try {
            float[] uv = ensureIconInAtlas(cacheKey, img);
            if (uv == null) return null; // atlas plein, déjà journalisé
            return new Object[]{ uv, atlasView, atlasSampler };
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DRect] guiAtlasEntry('" + cacheKey + "'): " + t);
            return null;
        }
    }

    /** @return {@code [u0,v0,u1,v1]} du sous-rect de {@code cacheKey} dans l'atlas partagé, {@code null} si l'atlas est plein (icône ignorée — journalisé une fois, voir currentStage/failureLogCount habituels). */
    private static float[] ensureIconInAtlas(String cacheKey, BufferedImage img) throws Exception {
        float[] cached = ICON_UV.get(cacheKey);
        if (cached != null) return cached;

        int w = img.getWidth(), h = img.getHeight();
        if (shelfX + w + ATLAS_PADDING > ATLAS_SIZE) {
            shelfX = ATLAS_PADDING;
            shelfY += shelfRowH + ATLAS_PADDING;
            shelfRowH = 0;
        }
        if (shelfY + h + ATLAS_PADDING > ATLAS_SIZE) {
            LauncherLog.err("[UiRenderer] UiTextBlaze3D: atlas d'icônes plein (" + ATLAS_SIZE + "x" + ATLAS_SIZE + "), '" + cacheKey + "' ignorée");
            return null;
        }
        int px = shelfX, py = shelfY;
        shelfX += w + ATLAS_PADDING;
        shelfRowH = Math.max(shelfRowH, h);

        Object device = mGetDevice.invoke(null);
        ensureAtlasTexture(device);
        Object nativeImage = bufferedImageToNativeImage(img, w, h);
        Object encoder = mCreateCommandEncoder.invoke(device);
        // writeToTexture(target, source, mipLevel, depth, offsetX, offsetY, width, height, skipPixels, skipRows)
        // — écrit SEULEMENT le sous-rect de cette icône, pas l'atlas entier.
        mWriteToTextureMip.invoke(encoder, atlasTexture, nativeImage, 0, 0, px, py, w, h, 0, 0);

        float[] uv = {
            px / (float) ATLAS_SIZE, py / (float) ATLAS_SIZE,
            (px + w) / (float) ATLAS_SIZE, (py + h) / (float) ATLAS_SIZE
        };
        ICON_UV.put(cacheKey, uv);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: icône '" + cacheKey + "' packée dans l'atlas (w=" + w + " h=" + h + " @" + px + "," + py + ")");
        return uv;
    }

    /**
     * Appelé depuis {@code UiRenderer.drawRoundedRectHud} — même file que le
     * texte (voir plus haut), pour un ordre de composition GARANTI correct :
     * fond DERRIÈRE, texte DEVANT, exactement l'ordre d'appel d'origine, au
     * lieu du GL brut (TAIL) qui composait TOUJOURS par-dessus le texte déjà
     * présenté (HEAD), assombrissant le texte sous un fond semi-transparent.
     */
    public static void queueRect(float x0, float y0, float x1, float y1, float radius, UiColor color, int vpWidth, int vpHeight) {
        queueRect(x0, y0, x1, y1, radius, radius, radius, radius, color, vpWidth, vpHeight);
    }

    /**
     * Rayon PAR COIN — voir {@link Blaze3DCore#RECT_FRAGMENT_SRC} pour la
     * convention {@code radiusTopLeft/TopRight/BottomLeft/BottomRight}
     * (relative à y0/y1 tels que passés, pas à l'axe GL). Remplace le hack
     * "2 rects superposés" (un arrondi + un plat par-dessus pour annuler
     * l'arrondi d'un côté — voir {@code UiMainMenuScreen#drawIconGrid}) par
     * un seul draw, plus d'artefact de chevauchement au raccord.
     */
    public static void queueRect(float x0, float y0, float x1, float y1,
                                  float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                  UiColor color, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawRect(x0, y0, x1, y1, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight, color, vpWidth, vpHeight));
    }

    /**
     * Empile une icône RGBA quelconque (pastille de mod/pack) — même file que
     * texte/rect (voir plus haut), même garantie de z-order. {@code img} est
     * DÉCODÉE/REDIMENSIONNÉE par l'appelant (voir UiRenderer.drawIcon) ;
     * {@code cacheKey} identifie la TEXTURE GPU (créée une seule fois, voir
     * ensureIconTexture) — jamais l'image Java elle-même, qui peut être
     * regénérée/re-décodée sans repayer le coût GPU si la clé ne change pas.
     */
    public static void queueIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, int vpWidth, int vpHeight) {
        queueIcon(cacheKey, img, x0, y0, x1, y1, 1f, vpWidth, vpHeight);
    }

    /**
     * Variante avec opacité — voir {@code UiRenderer#drawIcon(..., alpha, ...)}
     * pour le pourquoi (fondu d'entrée sur contenu asynchrone). {@code alpha}
     * remplace le 4ᵉ composant du ColorModulator (voir {@link #drawIcon}),
     * resté fixe à 1.0 partout ailleurs (couleurs réelles de l'image
     * inchangées, seule l'opacité globale varie).
     */
    public static void queueIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, float alpha, int vpWidth, int vpHeight) {
        if (!isAvailable() || img == null) return;
        Blaze3DCore.enqueue(() -> drawIcon(cacheKey, img, x0, y0, x1, y1, alpha, vpWidth, vpHeight));
    }

    // ── Rectangle arrondi (fond de panneau HUD) — pipeline dédié rectPipeline
    // (voir Blaze3DCore.RECT_FRAGMENT_SRC), coins calculés analytiquement par
    // pixel, un seul quad (plus de 9-slice/masque-texture, voir ci-dessous).
    /**
     * Coins arrondis ANALYTIQUES (voir {@code Blaze3DCore.RECT_FRAGMENT_SRC},
     * même formule que {@code UiPrimitiveRenderer.FRAGMENT_SRC} 1.8.9) —
     * remplace l'ancien masque-texture (résolution finie, visible dès qu'on
     * zoome assez — voir project_home_shader_pipeline en mémoire projet).
     * UN SEUL quad (le shader gère le rayon sur toute la surface, plus besoin
     * d'isoler les 4 coins) — {@code radius=0} fonctionne nativement, plus
     * de branchement petit/grand rayon.
     */
    private static boolean drawRect(float x0, float y0, float x1, float y1,
                                     float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                     UiColor color, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(rect)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(rect)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(rect)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float maxR = Math.min((x1 - x0) / 2f, (y1 - y0) / 2f);
            float rTL = Math.max(0f, Math.min(radiusTopLeft, maxR));
            float rTR = Math.max(0f, Math.min(radiusTopRight, maxR));
            float rBL = Math.max(0f, Math.min(radiusBottomLeft, maxR));
            float rBR = Math.max(0f, Math.min(radiusBottomRight, maxR));

            currentStage = "getDevice(rect)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(rect)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF; // couleur réelle appliquée via DynamicTransforms/ColorModulator
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
            int vertexCount = 4;
            verts.flip();

            currentStage = "ensureVertexBuffer(rect)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(rect)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(rect)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(rect)";
            Object identity4 = identityMatrix4f();
            Object colorMod = ctorVector4f.newInstance(color.r, color.g, color.b, color.a);
            Object zero3 = zeroVector3f();
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, colorMod, zero3, identity4);

            currentStage = "ensureProjectionBuffer(rect)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "writeRectParams(rect)";
            Object rectParamsSlice = writeRectParams(device, encoder, x0, y0, x1, y1, rTL, rTR, rBL, rBR);

            currentStage = "createRenderPass(rect)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_rect";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(rect)";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                ShaderPipelineFactory.precompile(device, rectPipeline, rectShaderSource);
                mSetPipeline.invoke(pass, rectPipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(rect)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(rect)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(rect)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(rect)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "setUniform(RectParams)(rect)";
                mSetUniformSlice.invoke(pass, "RectParams", rectParamsSlice);
                currentStage = "setVertexBuffer(rect)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(rect)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(rect)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(rect)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(rect)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawRect a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    // ── Icône RGBA quelconque (pastille de mod/pack) — MÊME structure que
    // drawRect, mais un SEUL quad plein (pas de 9-slice/coins arrondis — une
    // icône rectangulaire simple) échantillonnant la VRAIE texture de
    // l'icône (Sampler0), pas le masque de coin. ColorModulator reste blanc,
    // seul son alpha varie (paramètre {@code alpha} — fondu d'entrée sur
    // contenu asynchrone) : les couleurs RGB réelles de l'image sont
    // préservées telles quelles, comme pour un rect à dégradé (couleur
    // portée par la texture ici, pas par sommet).
    private static boolean drawIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, float alpha, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(icon)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(icon)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(icon)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            currentStage = "ensureIconInAtlas";
            float[] uv = ensureIconInAtlas(cacheKey, img);
            if (uv == null) return false; // atlas plein, déjà journalisé
            currentStage = "ensureWhiteTexture(icon)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(icon)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(icon)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            // UV = sous-rect de cette icône DANS L'ATLAS partagé (plus le
            // 0-1 plein d'une texture dédiée, voir ensureIconInAtlas) — même
            // correspondance top/bottom↔v0/v1 que le texte (yTop↔v0 haut de
            // l'image, yBottom↔v1 bas), voir putVertexPCTL/putRectQuad pour
            // la convention Y-UP déjà établie.
            float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
            putVertexPCTL(verts, x0, y1, rgba, u0, v0, light0, light1);
            putVertexPCTL(verts, x0, y0, rgba, u0, v1, light0, light1);
            putVertexPCTL(verts, x1, y0, rgba, u1, v1, light0, light1);
            putVertexPCTL(verts, x1, y1, rgba, u1, v0, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(icon)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(icon)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(icon)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(icon)";
            Object identity4 = identityMatrix4f();
            // RGB pass-through (vraies couleurs de l'image) — seul le composant
            // alpha varie (voir queueIcon(..., alpha, ...) / UiRenderer#drawIcon).
            Object white4 = ctorVector4f.newInstance(1f, 1f, 1f, alpha);
            Object zero3 = zeroVector3f();
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, white4, zero3, identity4);

            currentStage = "ensureProjectionBuffer(icon)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass(icon)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_icon";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(icon)";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                ShaderPipelineFactory.precompile(device, homePipeline, homeShaderSource);
                mSetPipeline.invoke(pass, homePipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(icon)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(icon)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(icon)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(icon)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "bindTexture(Sampler0)(icon)";
                mBindTexture.invoke(pass, "Sampler0", atlasView, atlasSampler);
                currentStage = "bindTexture(Sampler2)(icon)";
                mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
                currentStage = "setVertexBuffer(icon)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(icon)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(icon)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(icon)";
                mDrawIndexed.invoke(pass, 0, 0, 6, 1);
            } finally {
                currentStage = "closePass(icon)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawIcon a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    // ── Rects BATCHÉS (roadmap Phase 5.5) — N rects (bornes/couleur propres,
    // MÊME rayon partagé) en UN SEUL draw call, voir Blaze3DCore.RECT_BATCH_
    // FRAGMENT_SRC pour le calcul de SDF en espace local. Empilé dans la
    // MÊME file que tout le reste (Blaze3DCore.enqueue) — occupe UN SEUL
    // "slot" Z, comme n'importe quel autre dessin, jamais de réordonnancement
    // avec les dessins interleavés autour (voir le commentaire de section sur
    // ensureIconInAtlas pour pourquoi une fusion inter-widgets n'est PAS
    // tentée — ce batch est un opt-in explicite côté appelant, pas un
    // regroupement automatique).

    /**
     * @param bounds  un {@code float[4]} {@code {x0,y0,x1,y1}} par rect.
     * @param colors  une couleur par rect, même taille que {@code bounds}.
     * @param radius  rayon PARTAGÉ par tout le batch (pas par coin — voir le commentaire de {@link Blaze3DCore#RECT_BATCH_VERTEX_SRC}).
     */
    public static void queueRectBatch(float[][] bounds, UiColor[] colors, float radius, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawRectBatch(bounds, colors, radius, vpWidth, vpHeight));
    }

    /** Toggle via {@code /yf batchpoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean batchTestEnabled = false;

    /** 40 rects de couleurs/tailles variées, MÊME rayon, en UN SEUL draw call — preuve de mécanisme, dessiné en direct (comme drawTestPanel/drawTestParagraph). */
    public static void drawTestBatch(int vpWidth, int vpHeight) {
        int cols = 10, rows = 4;
        float cell = 44f, gap = 8f, x0 = 40f, y0 = 200f;
        float[][] bounds = new float[cols * rows][4];
        UiColor[] colors = new UiColor[cols * rows];
        int i = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float x = x0 + c * (cell + gap), y = y0 + r * (cell + gap);
                bounds[i] = new float[]{ x, y, x + cell, y + cell };
                float t = (r * cols + c) / (float) (cols * rows - 1);
                colors[i] = UiColor.lerp(new UiColor(139, 124, 255, 255), new UiColor(230, 95, 95, 255), t);
                i++;
            }
        }
        // Appel DIRECT (pas queueRectBatch/enqueue) — même convention que
        // Blaze3DBlur.drawTestPanel : un POC isolé n'a pas besoin de passer
        // par la file différée (flushQueued, appelée depuis un tout autre
        // point du frame, voir GuiFlushMixin), dessine directement ici.
        drawRectBatch(bounds, colors, 10f, vpWidth, vpHeight);
    }

    private static boolean drawRectBatch(float[][] bounds, UiColor[] colors, float radius, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        if (bounds.length == 0) return true;
        try {
            currentStage = "minecraftClient(rectbatch)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(rectbatch)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(rectbatch)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            currentStage = "getDevice(rectbatch)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(rectbatch)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int n = bounds.length;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(n * 4 * 28);
            for (int i = 0; i < n; i++) {
                float x0 = bounds[i][0], y0 = bounds[i][1], x1 = bounds[i][2], y1 = bounds[i][3];
                UiColor c = colors[i];
                int ri = Math.round(c.r * 255f), gi = Math.round(c.g * 255f), bi = Math.round(c.b * 255f), ai = Math.round(c.a * 255f);
                int rgba = (ai << 24) | (bi << 16) | (gi << 8) | ri;
                short w = (short) Math.round(x1 - x0), h = (short) Math.round(y1 - y0);
                putVertexPCTL(verts, x0, y1, rgba, 0f, 0f, w, h);
                putVertexPCTL(verts, x0, y0, rgba, 0f, 1f, w, h);
                putVertexPCTL(verts, x1, y0, rgba, 1f, 1f, w, h);
                putVertexPCTL(verts, x1, y1, rgba, 1f, 0f, w, h);
            }
            verts.flip();

            currentStage = "ensureVertexBuffer(rectbatch)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(rectbatch)";
            Object identity4 = identityMatrix4f();
            Object neutralColor = ctorVector4f.newInstance(1f, 1f, 1f, 1f); // couleur déjà portée par sommet
            Object zero3 = zeroVector3f();
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, neutralColor, zero3, identity4);

            currentStage = "ensureProjectionBuffer(rectbatch)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "writeBatchParams(rectbatch)";
            Object batchParamsSlice = writeBatchParams(device, encoder, radius);

            currentStage = "createRenderPass(rectbatch)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_rectbatch";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(rectbatch)";
                ShaderPipelineFactory.precompile(device, batchPipeline, batchShaderSource);
                mSetPipeline.invoke(pass, batchPipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(rectbatch)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(rectbatch)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(rectbatch)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(rectbatch)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "setUniform(BatchParams)(rectbatch)";
                mSetUniformSlice.invoke(pass, "BatchParams", batchParamsSlice);
                currentStage = "setVertexBuffer(rectbatch)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(rectbatch)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = n * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(rectbatch)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(rectbatch)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(rectbatch)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawRectBatch a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }
}
