package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        String[] paths = new String[2 + 3 * legacyNames.length];
        int i = 0;
        paths[i++] = "textures/item/" + name + ".png";
        paths[i++] = "textures/block/" + name + ".png";
        for (String legacy : legacyNames) {
            paths[i++] = "textures/item/" + legacy + ".png";
            paths[i++] = "textures/items/" + legacy + ".png";
            // 1.8.9 : ce qui se pose (torche de redstone…) a sa texture avec les blocs.
            paths[i++] = "textures/blocks/" + legacy + ".png";
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

    // ── Le pack de ressources change ────────────────────────────────────────
    //
    // Les images sont gardées pour la partie : un pack activé en cours de
    // route (retour du 2026-11-21 : boutons du jeu foncés, les nôtres restés
    // ceux d'origine) n'aurait jamais été vu. Le jeu ne nous prévient pas d'un
    // rechargement : à l'ouverture d'un écran, on relit le fichier du bouton
    // (moins d'un kilo-octet) et, s'il a changé, tout est oublié.

    private static int packSignature;
    private static int generation;

    /** À l'ouverture d'un écran qui montre des textures du jeu. Ne coûte qu'une petite lecture. */
    public static synchronized void checkPack() {
        byte[] probe = ClientData.resourceBytes("minecraft", "textures/gui/sprites/widget/button.png");
        if (probe == null) probe = ClientData.resourceBytes("minecraft", "textures/gui/widgets.png");
        int signature = probe == null ? 0 : java.util.Arrays.hashCode(probe);
        if (signature == packSignature) return;
        if (packSignature != 0) {
            CACHE.clear();
            MISSING.clear();
            SPRITES.clear();
            generation++;
            LauncherLog.info("[McTexture] pack de ressources changé : textures du jeu relues");
        }
        packSignature = signature;
    }

    /**
     * La clé d'atlas d'une texture du jeu : elle change avec le pack, sans
     * quoi le moteur garderait l'ancienne image sous la même clé.
     */
    public static String key(String name) {
        return "mc:" + generation + ":" + name;
    }

    /**
     * Comment le jeu étire le bouton, lu dans son {@code .mcmeta} : taille
     * logique en pixels du jeu, bordures, et le mode — {@code nine_slice}
     * (bords fixes, centre répété, ou étiré avec {@code stretch_inner}) ou
     * {@code stretch}. La 1.8.9 n'a pas de description : moitié gauche prise
     * au début, moitié droite à la fin ({@code halves}).
     */
    private static final class ButtonSprite {
        BufferedImage image;
        int logicalW = 200, logicalH = 20;
        int borderL = 3, borderR = 3, borderT = 3, borderB = 3;
        boolean stretch, stretchInner, halves;
    }

    /**
     * Dessine le bouton du jeu — texture du pack actif, découpée comme le jeu
     * la découpe — dans le rectangle donné (pixels de fenêtre, y vers le haut,
     * coin bas-gauche), à l'échelle {@code gs} pixels de fenêtre par pixel du
     * jeu. Rend {@code false} si la texture manque.
     *
     * <p>Une image par état (normal, survolé), quelle que soit la largeur :
     * une image par largeur remplissait l'atlas d'icônes, qui se vidait et
     * faisait recharger toutes les icônes du menu.
     */
    public static boolean drawButton(UiRenderer r, float x, float y, float w, float h, float gs, boolean hover,
                                     float alpha, int vw, int vh) {
        ButtonSprite s = buttonSprite(hover);
        if (s == null) return false;
        float lw = w / gs, lh = h / gs;
        int c = (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xFFFFFF;
        com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch batch =
            new com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch();
        // Colonnes : {x0, x1 à l'écran en pixels du jeu, u0, u1 en pixels logiques de la texture}.
        List<float[]> cols = new ArrayList<>();
        if (s.stretch) {
            cols.add(new float[]{ 0, lw, 0, s.logicalW });
        } else if (s.halves) {
            float left = (float) Math.floor(lw / 2f);
            cols.add(new float[]{ 0, left, 0, left });
            cols.add(new float[]{ left, lw, s.logicalW - (lw - left), s.logicalW });
        } else {
            float bl = Math.min(s.borderL, lw / 2f), br = Math.min(s.borderR, lw / 2f);
            cols.add(new float[]{ 0, bl, 0, bl });
            float inner = s.logicalW - s.borderL - s.borderR;
            float x0 = bl, x1 = lw - br;
            if (s.stretchInner || inner <= 0) {
                if (x1 > x0) cols.add(new float[]{ x0, x1, s.borderL, s.logicalW - s.borderR });
            } else {
                for (float cx = x0; cx < x1; cx += inner) {
                    float part = Math.min(inner, x1 - cx);
                    cols.add(new float[]{ cx, cx + part, s.borderL, s.borderL + part });
                }
            }
            cols.add(new float[]{ lw - br, lw, s.logicalW - br, s.logicalW });
        }
        // Rangées : la hauteur du jeu est celle de la texture (20) ; au-delà, bords fixes et centre étiré.
        List<float[]> rows = new ArrayList<>();
        if (s.stretch || s.halves || Math.abs(lh - s.logicalH) < 0.01f) {
            rows.add(new float[]{ 0, lh, 0, s.logicalH });
        } else {
            float bt = Math.min(s.borderT, lh / 2f), bb = Math.min(s.borderB, lh / 2f);
            rows.add(new float[]{ 0, bt, 0, bt });
            if (lh - bb > bt) rows.add(new float[]{ bt, lh - bb, s.borderT, s.logicalH - s.borderB });
            rows.add(new float[]{ lh - bb, lh, s.logicalH - bb, s.logicalH });
        }
        float top = y + h;
        for (float[] row : rows) {
            // y vers le haut à l'écran, v depuis le haut de l'image.
            float sy1 = top - row[0] * gs, sy0 = top - row[1] * gs;
            float v0 = row[2] / s.logicalH, v1 = row[3] / s.logicalH;
            for (float[] col : cols) {
                float sx0 = x + col[0] * gs, sx1 = x + col[1] * gs;
                float u0 = col[2] / s.logicalW, u1 = col[3] / s.logicalW;
                batch.add(sx0, sy0, sx1, sy0, sx1, sy1, sx0, sy1,
                    u0, v1, u1, v1, u1, v0, u0, v0, c, c, c, c);
            }
        }
        r.drawTexturedQuads(key("button:" + hover), s.image, batch, vw, vh);
        return true;
    }

    private static final Map<Boolean, ButtonSprite> SPRITES = new HashMap<>();

    private static synchronized ButtonSprite buttonSprite(boolean hover) {
        ButtonSprite cachedSprite = SPRITES.get(hover);
        if (cachedSprite != null) return cachedSprite;
        String key = "button-sprite:" + hover;
        if (failedRecently(key)) return null;
        String path = hover ? "textures/gui/sprites/widget/button_highlighted.png" : "textures/gui/sprites/widget/button.png";
        BufferedImage src = raw(path);
        ButtonSprite s = new ButtonSprite();
        if (src != null) {
            readScaling(s, ClientData.resourceBytes("minecraft", path + ".mcmeta"));
        } else {
            BufferedImage sheet = raw("textures/gui/widgets.png");
            if (sheet != null && sheet.getWidth() >= 200) {
                // 1.8.9 : planche de 256, le bouton à (0, 66), survolé à (0, 86). Un pack en haute définition la multiplie.
                int f = sheet.getWidth() / 256;
                src = sheet.getSubimage(0, (hover ? 86 : 66) * f, 200 * f, 20 * f);
                s.halves = true;
            }
        }
        if (src == null) {
            markMissing(key);
            return null;
        }
        // Agrandie net, environ 800 de large : le filtre linéaire de l'atlas ne voit plus que de grands carrés.
        int k = Math.max(1, 800 / src.getWidth());
        s.image = k == 1 ? src : scaleBy(src, k);
        SPRITES.put(hover, s);
        return s;
    }

    /** Le bloc {@code gui.scaling} d'un {@code .mcmeta} ; sans lui, les valeurs du jeu d'origine. */
    private static void readScaling(ButtonSprite s, byte[] meta) {
        if (meta == null) return;
        String text = new String(meta, java.nio.charset.StandardCharsets.UTF_8);
        String type = match(text, "\"type\"\\s*:\\s*\"([a-z_]+)\"");
        s.stretch = "stretch".equals(type);
        s.stretchInner = "true".equals(match(text, "\"stretch_inner\"\\s*:\\s*(true|false)"));
        s.logicalW = intOr(match(text, "\"width\"\\s*:\\s*(\\d+)"), s.logicalW);
        s.logicalH = intOr(match(text, "\"height\"\\s*:\\s*(\\d+)"), s.logicalH);
        String border = match(text, "\"border\"\\s*:\\s*(\\d+)");
        if (border != null) {
            s.borderL = s.borderR = s.borderT = s.borderB = Integer.parseInt(border);
        } else {
            s.borderL = intOr(match(text, "\"left\"\\s*:\\s*(\\d+)"), s.borderL);
            s.borderR = intOr(match(text, "\"right\"\\s*:\\s*(\\d+)"), s.borderR);
            s.borderT = intOr(match(text, "\"top\"\\s*:\\s*(\\d+)"), s.borderT);
            s.borderB = intOr(match(text, "\"bottom\"\\s*:\\s*(\\d+)"), s.borderB);
        }
    }

    private static String match(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int intOr(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static BufferedImage scaleBy(BufferedImage src, int k) {
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

    /** Dessine une texture sans la déformer ; rien si elle manque. */
    public static void draw(UiRenderer r, BufferedImage img, String key, float x, float y, float w, float h,
                            float alpha, int vw, int vh) {
        if (img == null) return;
        r.drawIcon(key(key), img, x, y, w, h, alpha, vw, vh);
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
                if (img != null) return plainGray(img);
            } catch (Exception e) {
                LauncherLog.err("[McTexture] " + path + " illisible : " + e);
            }
        }
        return null;
    }

    /**
     * Un PNG en niveaux de gris, recopié valeur pour valeur en ARGB.
     *
     * <p>Java range le gris d'un PNG dans un espace de couleur LINÉAIRE, et
     * {@code getRGB} le convertit en sRGB : le gris 111 sort à environ 182.
     * Le jeu, lui, prend la valeur telle quelle. Les packs qui enregistrent
     * leurs boutons en gris (« Ice Cream ») donnaient donc des boutons bien
     * trop clairs (retour du 2026-11-21). Les autres images ne changent pas.
     */
    private static BufferedImage plainGray(BufferedImage img) {
        if (img.getColorModel().getColorSpace().getType() != java.awt.color.ColorSpace.TYPE_GRAY) return img;
        java.awt.image.Raster raster = img.getRaster();
        int bands = raster.getNumBands();
        int bits = img.getColorModel().getComponentSize(0);
        int max = (1 << bits) - 1;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[bands];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.getPixel(x, y, px);
                int g = px[0] * 255 / max;
                int a = bands > 1 ? px[1] * 255 / max : 255;
                out.setRGB(x, y, (a << 24) | (g << 16) | (g << 8) | g);
            }
        }
        return out;
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
