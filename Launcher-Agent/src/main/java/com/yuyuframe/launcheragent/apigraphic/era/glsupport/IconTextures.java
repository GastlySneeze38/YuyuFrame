package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Image → texture GPU, mise en cache par clé. Jumeau de
 * {@link FontAtlasTextures} pour les icônes arbitraires (icônes Modrinth,
 * bannières, images distantes), extrait de {@code UiPrimitiveRenderer} le
 * 2026-09-09.
 *
 * <p>Même raison d'être ici que son jumeau : le bloc ne connaît que
 * {@link GlBridge}, il ne dépend d'aucune des deux ères GL, et les DEUX
 * chemins de dessin d'icône (gl2 et gl3) l'utilisent. C'est du support GL
 * partagé, pas du rendu.
 *
 * <p>Comme pour les polices, une valeur {@code -1} mémorisée signifie « échec
 * déjà constaté » : sans elle, un upload qui rate serait retenté à chaque
 * frame.
 */
public final class IconTextures {

    private final GlBridge gl;

    public IconTextures(GlBridge gl) {
        this.gl = gl;
    }

    private final Map<String, Integer> iconTextures = new HashMap<>();

    /** Upload GL brut (glTexImage2D), mis en cache par cacheKey — voir FontAtlasTextures#createFontTextureRaw pour le même motif appliqué aux polices. */
    /**
     * Texture GL pour cette image, uploadée à la première demande puis mise en
     * cache sous {@code cacheKey} — upload brut par {@code glTexImage2D}, même
     * motif que {@code FontAtlasTextures#createFontTextureRaw} pour les polices.
     *
     * @return l'identifiant de texture, ou {@code -1} si l'upload a échoué
     */
    public int ensureIconTexture(String cacheKey, BufferedImage img) {
        Integer cached = iconTextures.get(cacheKey);
        if (cached != null) return cached;
        try {
            int w = img.getWidth(), h = img.getHeight();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    buf.put((byte) ((argb >> 16) & 0xFF)); // R
                    buf.put((byte) ((argb >> 8) & 0xFF));  // G
                    buf.put((byte) (argb & 0xFF));         // B
                    buf.put((byte) ((argb >> 24) & 0xFF)); // A
                }
            }
            buf.flip();

            int texId = gl.glGenTextures();
            gl.glBindTexture(0x0DE1, texId);
            gl.glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
            gl.glTexParameteri(0x0DE1, 0x2801, 0x2601); // GL_TEXTURE_MIN_FILTER, GL_LINEAR (pas de mipmap — icônes rarement minifiées fortement)
            gl.glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            gl.glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            gl.glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
            // Même précaution ZGC que createFontTextureRaw (voir son
            // commentaire pour le pourquoi) — coût négligeable ici (upload
            // UNE SEULE FOIS par icône, jamais par frame).
            gl.glFinish();
            GlBridge.reachabilityFence(buf);

            iconTextures.put(cacheKey, texId);
            LauncherLog.ui(1, "[UiRenderer] icône '" + cacheKey + "' uploadée (glTexImage2D), texId=" + texId + " w=" + w + " h=" + h);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureIconTexture(" + cacheKey + "): " + t);
            iconTextures.put(cacheKey, -1);
            return -1;
        }
    }
}
