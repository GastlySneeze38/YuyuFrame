package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Les textures du jeu dans l'interface : objets, tête du skin, barre d'XP.
 *
 * <h2>Lues dans le jeu, jamais embarquées</h2>
 *
 * Comme la police de Minecraft ({@code MinecraftFont}), elles viennent du
 * gestionnaire de ressources : c'est donc celles du pack actif, et elles ne
 * pèsent rien dans l'agent. Les chemins changent selon la version (la 1.8.9
 * range les objets sous {@code textures/items/} et la barre d'XP dans
 * {@code icons.png}) : chaque texture se décrit par plusieurs candidats, le
 * premier trouvé l'emporte.
 *
 * <h2>Des pixels nets</h2>
 *
 * Le moteur filtre les icônes en linéaire, ce qui rend flou un objet de
 * 16 pixels affiché à 32. L'image est donc agrandie ICI, au plus proche
 * voisin, jusqu'à 256 pixels de côté environ : à la taille d'affichage, le
 * filtre linéaire ne voit plus que de grands carrés et les bords restent nets.
 *
 * <p>Une texture introuvable (version qui ne l'a pas, pack qui la retire)
 * rend {@code null} une fois pour toutes : l'appelant dessine son repli.
 */
public final class McTexture {
    private McTexture() {}

    /** Côté visé de l'image agrandie. */
    private static final int TARGET = 256;

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static final Map<String, Long> MISSING = new HashMap<>();
    /** Les ressources ne sont pas lisibles au tout début : un échec se réessaie, mais pas à chaque image. */
    private static final long RETRY_MS = 3000L;

    /** Un objet du jeu par son nom moderne ({@code "mace"}), puis ses noms anciens. */
    public static BufferedImage item(String name, String... legacyNames) {
        String[] paths = new String[2 + 2 * legacyNames.length];
        int i = 0;
        paths[i++] = "textures/item/" + name + ".png";
        paths[i++] = "textures/block/" + name + ".png";
        for (String legacy : legacyNames) {
            paths[i++] = "textures/item/" + legacy + ".png";
            paths[i++] = "textures/items/" + legacy + ".png";
        }
        return image("item:" + name, null, paths);
    }

    /** Tête par défaut d'un joueur (visage + chapeau du skin de Steve), 8 x 8 agrandi. */
    public static BufferedImage defaultFace() {
        String key = "face:steve";
        BufferedImage cached = cached(key);
        if (cached != null || failedRecently(key)) return cached;
        BufferedImage skin = raw("textures/entity/player/wide/steve.png", "textures/entity/steve.png");
        if (skin == null || skin.getWidth() < 64) {
            markMissing(key);
            return null;
        }
        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = face.createGraphics();
        g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
        g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
        g.dispose();
        return store(key, upscale(face));
    }

    /** Fond de la barre d'XP du jeu (182 x 5), ou {@code null}. */
    public static BufferedImage xpBackground() {
        return image("xp:bg", new int[]{ 0, 64, 182, 5 },
            "textures/gui/sprites/hud/experience_bar_background.png", "textures/gui/icons.png");
    }

    /** Remplissage de la barre d'XP du jeu (182 x 5), ou {@code null}. */
    public static BufferedImage xpProgress() {
        return image("xp:fill", new int[]{ 0, 69, 182, 5 },
            "textures/gui/sprites/hud/experience_bar_progress.png", "textures/gui/icons.png");
    }

    /**
     * Le bouton du jeu à une largeur donnée, en pixels de GUI (20 de haut),
     * agrandi {@code k} fois au plus proche voisin, ou {@code null}.
     *
     * <p>Comme le jeu : la moitié gauche est prise au début de la texture
     * (200 x 20), la moitié droite à sa fin — les bords restent ceux du
     * dessin quelle que soit la largeur. Le sprite {@code widget/button}
     * des versions récentes, ou la planche {@code widgets.png} de la 1.8.9.
     */
    public static BufferedImage button(int guiWidth, boolean hover, int k) {
        int w = Math.max(4, Math.min(200, guiWidth));
        k = Math.max(1, Math.min(8, k));
        String key = "button:" + w + ":" + hover + ":" + k;
        BufferedImage cached = cached(key);
        if (cached != null || failedRecently(key)) return cached;
        BufferedImage src = buttonSource(hover);
        if (src == null) {
            markMissing(key);
            return null;
        }
        int left = w / 2, right = w - left;
        // Un pack en haute définition multiplie la texture : on lit un pixel du jeu sur f.
        int f = Math.max(1, src.getWidth() / 200);
        BufferedImage out = new BufferedImage(w * k, 20 * k, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < w; x++) {
                int sx = (x < left ? x : 200 - right + (x - left)) * f;
                int argb = src.getRGB(Math.min(src.getWidth() - 1, sx), Math.min(src.getHeight() - 1, y * f));
                for (int j = 0; j < k; j++) {
                    for (int i = 0; i < k; i++) out.setRGB(x * k + i, y * k + j, argb);
                }
            }
        }
        return store(key, out);
    }

    /** La texture entière du bouton (200 x 20 en pixels du jeu), sans agrandissement. */
    private static BufferedImage buttonSource(boolean hover) {
        String key = "button-src:" + hover;
        BufferedImage cached = cached(key);
        if (cached != null || failedRecently(key)) return cached;
        BufferedImage img = raw(hover ? "textures/gui/sprites/widget/button_highlighted.png" : "textures/gui/sprites/widget/button.png");
        if (img == null) {
            BufferedImage sheet = raw("textures/gui/widgets.png");
            if (sheet != null && sheet.getWidth() >= 200) {
                // 1.8.9 : planche de 256, le bouton à (0, 66), survolé à (0, 86). Un pack en haute définition la multiplie.
                int f = sheet.getWidth() / 256;
                img = sheet.getSubimage(0, (hover ? 86 : 66) * f, 200 * f, 20 * f);
            }
        }
        if (img == null) {
            markMissing(key);
            return null;
        }
        return store(key, img);
    }

    /** Dessine une texture sans la déformer ; rien si elle manque. */
    public static void draw(UiRenderer r, BufferedImage img, String key, float x, float y, float w, float h,
                            float alpha, int vw, int vh) {
        if (img == null) return;
        r.drawIcon("mc:" + key, img, x, y, w, h, alpha, vw, vh);
    }

    // ── Chargement ──────────────────────────────────────────────────────────

    /**
     * @param atlasRegion {@code x, y, w, h} à découper dans les fichiers qui
     *                    sont des planches (l'{@code icons.png} de la 1.8.9),
     *                    appliqué seulement quand le fichier est plus grand
     */
    private static BufferedImage image(String key, int[] atlasRegion, String... paths) {
        BufferedImage cached = cached(key);
        if (cached != null || failedRecently(key)) return cached;
        BufferedImage img = raw(paths);
        if (img == null) {
            markMissing(key);
            return null;
        }
        if (atlasRegion != null && img.getWidth() >= 256) {
            img = img.getSubimage(atlasRegion[0], atlasRegion[1], atlasRegion[2], atlasRegion[3]);
        } else if (atlasRegion == null && img.getHeight() > img.getWidth() && img.getHeight() % img.getWidth() == 0) {
            // Texture animée (l'horloge de la 1.8.9) : une bande d'images, on garde la première.
            img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
        }
        return store(key, upscale(img));
    }

    private static BufferedImage raw(String... paths) {
        for (String path : paths) {
            try {
                byte[] bytes = ClientData.resourceBytes("minecraft", path);
                if (bytes == null) continue;
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) return img;
            } catch (Exception e) {
                LauncherLog.err("[McTexture] " + path + " illisible : " + e);
            }
        }
        return null;
    }

    /** Agrandissement au plus proche voisin, d'un facteur entier. */
    private static BufferedImage upscale(BufferedImage src) {
        int k = Math.max(1, TARGET / Math.max(src.getWidth(), src.getHeight()));
        if (k == 1) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w * k];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                for (int i = 0; i < k; i++) row[x * k + i] = argb;
            }
            for (int j = 0; j < k; j++) out.setRGB(0, y * k + j, w * k, 1, row, 0, w * k);
        }
        return out;
    }

    private static synchronized BufferedImage cached(String key) {
        return CACHE.get(key);
    }

    private static synchronized BufferedImage store(String key, BufferedImage img) {
        CACHE.put(key, img);
        MISSING.remove(key);
        return img;
    }

    private static synchronized boolean failedRecently(String key) {
        Long at = MISSING.get(key);
        return at != null && System.currentTimeMillis() - at < RETRY_MS;
    }

    private static synchronized void markMissing(String key) {
        MISSING.put(key, System.currentTimeMillis());
    }
}
