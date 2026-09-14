package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Atlas partagé des icônes arbitraires (pastilles Modrinth, bannières, images
 * distantes) — pendant gl3 de l'atlas de {@code Blaze3DRect} (roadmap
 * Phase 5.5).
 *
 * <p>Avant : une texture GPU par icône ({@code IconTextures}), donc une
 * allocation et un changement de texture liée par icône dessinée. Ici : UNE
 * texture {@value #ATLAS_SIZE}², chaque icône y occupe un sous-rectangle ; on
 * n'envoie au GPU que ce sous-rectangle ({@code glTexSubImage2D}), une seule
 * fois par clé.
 *
 * <h2>Rangement « par étagères »</h2>
 *
 * Même algorithme que Blaze3D : on avance en X sur l'étagère courante, et on
 * passe à l'étagère suivante (hauteur = plus haute icône posée) quand la ligne
 * déborde. {@value #PADDING} px de marge entre icônes contre le débordement du
 * filtrage bilinéaire.
 *
 * <h2>Écarts avec Blaze3D</h2>
 *
 * <ul>
 *   <li>Coordonnées de texture rentrées d'un demi-texel : le filtrage
 *       bilinéaire au bord d'une icône ne lit plus la marge transparente, le
 *       contour ne pâlit plus quand l'icône est agrandie.</li>
 *   <li>Atlas plein ou image plus grande que l'atlas : {@code null}, et
 *       l'appelant reprend le chemin « une texture par icône » au lieu de ne
 *       rien dessiner (Blaze3D ignore l'icône).</li>
 * </ul>
 */
public final class Gl3IconAtlas {

    static final int ATLAS_SIZE = 2048;
    private static final int PADDING = 2;

    private int texture = -1;
    private int shelfX = PADDING, shelfY = PADDING, shelfRowH;
    /** cacheKey → {u0, v0, u1, v1} ; {@code NO_SPACE} = ne rentre pas, déjà journalisé. */
    private final Map<String, float[]> uvs = new HashMap<>();
    private static final float[] NO_SPACE = new float[0];

    /** Texture de l'atlas, {@code -1} tant qu'aucune icône n'y est rangée. */
    public int texture() {
        return texture;
    }

    /**
     * Sous-rectangle de {@code cacheKey} dans l'atlas, rangé et envoyé à la
     * première demande.
     *
     * @return {@code {u0, v0, u1, v1}} (v0 = HAUT de l'image), ou {@code null}
     *         si l'icône ne rentre pas — l'appelant la dessine alors avec sa
     *         propre texture
     */
    public float[] uv(String cacheKey, BufferedImage img) {
        float[] cached = uvs.get(cacheKey);
        if (cached != null) return cached == NO_SPACE ? null : cached;

        int w = img.getWidth(), h = img.getHeight();
        if (w + 2 * PADDING > ATLAS_SIZE || h + 2 * PADDING > ATLAS_SIZE) {
            return refuse(cacheKey, "plus grande que l'atlas (" + w + "x" + h + ")");
        }
        if (shelfX + w + PADDING > ATLAS_SIZE) {
            shelfX = PADDING;
            shelfY += shelfRowH + PADDING;
            shelfRowH = 0;
        }
        if (shelfY + h + PADDING > ATLAS_SIZE) {
            return refuse(cacheKey, "atlas plein");
        }
        int px = shelfX, py = shelfY;

        try {
            if (texture == -1) {
                texture = Gl3Core.createTexture(ATLAS_SIZE, ATLAS_SIZE);
                LauncherLog.ui(1, "[Gl3IconAtlas] atlas d'icônes créé (" + ATLAS_SIZE + "x" + ATLAS_SIZE + ")");
            } else {
                Gl3Core.bindTexture0(texture);
            }
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, px, py, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba(img, w, h));
        } catch (Throwable t) {
            LauncherLog.err("[Gl3IconAtlas] envoi de '" + cacheKey + "' : " + t);
            uvs.put(cacheKey, NO_SPACE);
            return null;
        }

        shelfX += w + PADDING;
        shelfRowH = Math.max(shelfRowH, h);
        float s = ATLAS_SIZE, half = 0.5f;
        float[] uv = { (px + half) / s, (py + half) / s, (px + w - half) / s, (py + h - half) / s };
        uvs.put(cacheKey, uv);
        LauncherLog.ui(1, "[Gl3IconAtlas] icône '" + cacheKey + "' rangée (" + w + "x" + h + " @" + px + "," + py + ")");
        return uv;
    }

    private float[] refuse(String cacheKey, String why) {
        LauncherLog.warn("[Gl3IconAtlas] '" + cacheKey + "' hors atlas : " + why + " — texture dédiée");
        uvs.put(cacheKey, NO_SPACE);
        return null;
    }

    /** ARGB de {@link BufferedImage} → octets RGBA, ligne du HAUT en premier (ligne 0 de la texture). */
    private static ByteBuffer rgba(BufferedImage img, int w, int h) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
            }
        }
        buf.flip();
        return buf;
    }
}
