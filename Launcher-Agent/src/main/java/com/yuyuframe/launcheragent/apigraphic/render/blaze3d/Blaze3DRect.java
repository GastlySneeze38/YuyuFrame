package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

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

    // ── Textures d'icônes arbitraires (pastilles de mod/pack, une par cacheKey) ──
    // Même mécanisme que ensureTexture (police) — image RGBA quelconque au lieu
    // d'un atlas de glyphes, jamais de mip (icônes 32-96px, jamais minifiées
    // aussi violemment que le texte le plus petit de l'UI qui a motivé les mips).

    private static final Map<String, Object[]> ICON_TEXTURES = new HashMap<>(); // cacheKey -> [GpuTexture, GpuTextureView, GpuSampler]

    private static Object[] ensureIconTexture(String cacheKey, BufferedImage img) throws Exception {
        Object[] cached = ICON_TEXTURES.get(cacheKey);
        if (cached != null) return cached;

        int w = img.getWidth(), h = img.getHeight();
        Object nativeImage = bufferedImageToNativeImage(img, w, h);

        Object device = mGetDevice.invoke(null);
        final String label = "yuyuframe_icon_" + cacheKey;
        java.util.function.Supplier<String> labelSupplier = () -> label;
        Object texture = mCreateTexture.invoke(device, labelSupplier, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, w, h, 1, 1);
        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);
        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);

        Object[] result = {texture, textureView, sampler};
        ICON_TEXTURES.put(cacheKey, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: icône '" + cacheKey + "' créée (w=" + w + " h=" + h + ")");
        return result;
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
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object colorMod = ctorVector4f.newInstance(color.r, color.g, color.b, color.a);
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
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

            currentStage = "ensureIconTexture";
            Object[] icon = ensureIconTexture(cacheKey, img);
            Object iconView = icon[1], iconSampler = icon[2];
            currentStage = "ensureWhiteTexture(icon)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(icon)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(icon)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            // UV pleine image (0,0)-(1,1) — même correspondance top/bottom↔v0/v1
            // que le texte (yTop↔v0 haut de l'image, yBottom↔v1 bas), voir
            // putVertexPCTL/putRectQuad pour la convention Y-UP déjà établie.
            putVertexPCTL(verts, x0, y1, rgba, 0f, 0f, light0, light1);
            putVertexPCTL(verts, x0, y0, rgba, 0f, 1f, light0, light1);
            putVertexPCTL(verts, x1, y0, rgba, 1f, 1f, light0, light1);
            putVertexPCTL(verts, x1, y1, rgba, 1f, 0f, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(icon)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(icon)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(icon)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(icon)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            // RGB pass-through (vraies couleurs de l'image) — seul le composant
            // alpha varie (voir queueIcon(..., alpha, ...) / UiRenderer#drawIcon).
            Object white4 = ctorVector4f.newInstance(1f, 1f, 1f, alpha);
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
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
                mBindTexture.invoke(pass, "Sampler0", iconView, iconSampler);
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
}
