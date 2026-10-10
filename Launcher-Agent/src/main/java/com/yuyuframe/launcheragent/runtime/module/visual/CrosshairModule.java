package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingModal;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPixelGrid;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Crosshair personnalisé. Le vanilla est masqué par le point {@code
 * HUD_EXTRACT_CROSSHAIR} (un mixin par version), on dessine le nôtre à la
 * place. Deux façons de le faire :
 *
 * <ul>
 *   <li><b>Croix</b> : quatre barres (ou un point) réglées par des curseurs.</li>
 *   <li><b>Dessin</b> : une grille de {@value #GRID} × {@value #GRID} pixels,
 *       peinte à la main dans l'écran de configuration ({@link UiPixelGrid})
 *       ou remplie depuis une image. Une image importée DEVIENT le dessin :
 *       il n'y a qu'un seul modèle, donc une image se retouche ensuite au
 *       pixel. Dessinée en rectangles de couleur, sans texture : nette à toute
 *       taille, et rien à charger.</li>
 * </ul>
 *
 * L'écran de configuration montre le résultat dans son cadre d'aperçu
 * ({@link #drawConfigPreview}) — le même code de dessin que le HUD, agrandi.
 *
 * <h2>Barre de rechargement</h2>
 *
 * Masquer le crosshair de vanilla masque aussi sa barre de rechargement (le
 * même appel dessine les deux). On la redessine donc, À L'IDENTIQUE : les
 * deux vraies textures du jeu ({@code hud/crosshair_attack_indicator_*}, lues
 * par le gestionnaire de ressources, donc celles du pack actif), à la taille
 * et à la place de vanilla (16 × 4 pixels de GUI, sous le centre), visible
 * seulement pendant le rechargement. Rien n'y est réglable, sinon de la
 * montrer ou non — et ce réglage n'existe pas en 1.8.9, qui n'a pas de
 * rechargement. Seul écart avec vanilla : son mélange « couleurs inversées »
 * avec le décor n'est pas reproduit.
 */
public final class CrosshairModule extends LauncherModule {

    /** Côté de la grille de dessin. Impair : il y a une case du milieu, celle qui tombe sur le centre de l'écran. */
    public static final int GRID = 33;

    /** 0 = croix réglée par curseurs, 1 = dessin. */
    public int mode = 0;

    public float size = 8f;

    public float thickness = 2f;

    public float gap = 3f;

    public boolean dotMode = false;

    /** Couleur de la croix, et du pinceau du dessin. */
    public UiColor color = new UiColor(255, 255, 255, 255);

    /** Taille à l'écran d'un pixel du dessin. */
    public float pixelSize = 2f;

    public boolean showCooldownBar = true;

    /** Le dessin : {@code GRID × GRID} couleurs ARGB, 0 = vide, ligne 0 en haut. Édité en place par la grille. */
    private final int[] pixels = new int[GRID * GRID];

    /** Pourquoi le dernier import a échoué, ou {@code null}. Affiché dans les réglages. */
    private String importError;

    @Override
    protected void settings(SettingList s) {
        s.dropdown("mode", "Type", "Croix : quatre barres réglées par les curseurs. Dessin : la grille plus bas, peinte à la main ou remplie depuis une image.",
            "Réglages", new String[]{ "Croix", "Dessin" }, null, () -> mode, v -> mode = v);
        s.slider("size", "Taille", "", "Réglages", 1f, 32f, 1f, () -> mode == 0, () -> size, v -> size = v);
        s.slider("thickness", "Épaisseur", "", "Réglages", 1f, 6f, 1f, () -> mode == 0, () -> thickness, v -> thickness = v);
        s.slider("gap", "Espacement central", "", "Réglages", 0f, 16f, 1f, () -> mode == 0, () -> gap, v -> gap = v);
        s.toggle("dotMode", "Point au lieu d'une croix", "", "Réglages", () -> mode == 0, () -> dotMode, v -> dotMode = v);
        s.color("color", "Couleur", "Réglages", () -> color, v -> color = v);

        if (importError != null) {
            s.warning("importError", "Import impossible", importError, "Dessin");
        }
        s.custom("drawing", "Dessin", 290f, (x, y, w, h, commit) ->
            new UiPixelGrid(x, y, w, h, GRID, pixels, () -> color, () -> mode == 1,
                "Choisis le type « Dessin » pour dessiner", this::rebuildRuns, commit));
        s.info("drawingHelp", "Dessiner sa croix",
            "Clic gauche pour peindre avec la couleur choisie plus haut, clic droit pour effacer. La croix claire de la grille marque le centre de l'écran.",
            "Dessin");
        s.slider("pixelSize", "Taille d'un pixel", "La taille à l'écran d'une case du dessin.",
            "Dessin", 1f, 8f, 1f, () -> mode == 1, () -> pixelSize, v -> pixelSize = v);
        s.action("importImage", "Importer une image",
            "Remplace le dessin par une image PNG. Au-delà de 33 pixels de côté, elle est réduite.",
            "Dessin", this::askImage);
        s.action("clearDrawing", "Effacer le dessin", "", "Dessin", () -> {
            java.util.Arrays.fill(pixels, 0);
            rebuildRuns();
        });
        s.opaque("pixels", this::encodePixels, this::decodePixels);

        // Pas de rechargement avant la refonte du combat (1.9) : le réglage n'aurait rien à montrer.
        if (!"1.8.9".equals(AccessorRegistry.boundVersion())) {
            s.toggle("showCooldownBar", "Afficher la barre de rechargement",
                "La barre de vanilla sous le viseur, pendant le rechargement d'une attaque.",
                "Rechargement", null, () -> showCooldownBar, v -> showCooldownBar = v);
        }
    }

    public CrosshairModule() {
        super("custom-crosshair", "Crosshair personnalisé", "Remplace la croix de visée vanilla", false,
            HookPoint.HUD_EXTRACT_CROSSHAIR);
        // Voir LauncherModule.ICON_LOCAL_CROSSHAIR — aucune icône "crosshair"/
        // "réticule"/"viseur" trouvée dans le style icons8 utilisé partout
        // ailleurs (vérifié individuellement), un vrai réticule dessiné à la
        // main est de toute façon plus fidèle que "target" (cible en cercles
        // concentriques, PAS un viseur).
        iconUrl = ICON_LOCAL_CROSSHAIR;

        // Un dessin de départ, pour que le type « Dessin » ne s'ouvre pas sur
        // du vide : une croix fine. Remplacé par le dessin sauvegardé s'il y en a un.
        int mid = GRID / 2, white = 0xFFFFFFFF;
        for (int i = 2; i <= 5; i++) {
            pixels[mid * GRID + mid - i] = white;
            pixels[mid * GRID + mid + i] = white;
            pixels[(mid - i) * GRID + mid] = white;
            pixels[(mid + i) * GRID + mid] = white;
        }
        rebuildRuns();

        // dispatch()==true fait sauter le rendu vanilla dans le mixin du
        // crosshair de la version : la croix de vanilla disparaît quand ce
        // module est actif, la nôtre étant dessinée par onRenderOverlay.
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_CROSSHAIR, ctx -> isEnabled());
    }

    // ── Dessin du viseur ───────────────────────────────────────────────────

    private static boolean overlayErrorLogged;

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            drawCrosshair(renderer, vpWidth / 2f, vpHeight / 2f, 1f, true, vpWidth, vpHeight);
            if (showCooldownBar) drawCooldownBar(renderer, vpWidth, vpHeight);
        } catch (Throwable t) {
            if (!overlayErrorLogged) {
                overlayErrorLogged = true;
                LauncherLog.err("[CrosshairModule] onRenderOverlay: " + t);
            }
        }
    }

    /**
     * Le viseur, centré sur ({@code cx}, {@code cy}), agrandi {@code zoom}
     * fois — 1 en jeu, plus dans l'aperçu : c'est le même dessin.
     *
     * @param hud vrai pour le dessin en jeu, dont la géométrie est mémorisée d'une image à l'autre
     */
    private void drawCrosshair(UiRenderer renderer, float cx, float cy, float zoom, boolean hud,
                               int vpWidth, int vpHeight) {
        if (mode == 1) {
            drawDrawing(renderer, cx, cy, pixelSize * zoom, hud, vpWidth, vpHeight);
            return;
        }
        float t = thickness * zoom, g = gap * zoom, s = size * zoom;
        if (dotMode) {
            renderer.drawRoundedRect(cx - t, cy - t, cx + t, cy + t, t, color, vpWidth, vpHeight);
            return;
        }
        // Gauche / droite / haut / bas — 4 barres autour du centre, avec l'espacement "gap"
        renderer.drawRoundedRect(cx - g - s, cy - t / 2f, cx - g, cy + t / 2f, 0, color, vpWidth, vpHeight);
        renderer.drawRoundedRect(cx + g, cy - t / 2f, cx + g + s, cy + t / 2f, 0, color, vpWidth, vpHeight);
        renderer.drawRoundedRect(cx - t / 2f, cy - g - s, cx + t / 2f, cy - g, 0, color, vpWidth, vpHeight);
        renderer.drawRoundedRect(cx - t / 2f, cy + g, cx + t / 2f, cy + g + s, 0, color, vpWidth, vpHeight);
    }

    /** Demi-encombrement du viseur à zoom 1, pour que l'aperçu choisisse un agrandissement qui tient. */
    private float extent() {
        if (mode == 1) return GRID * pixelSize / 2f;
        return dotMode ? thickness : gap + size;
    }

    // Le dessin, réduit à des bandes : {colonne, ligne, longueur} par suite de
    // cases de même couleur. Refait à chaque modification, pas à chaque image.
    private int[] runs = new int[0];
    private UiColor[] runColors = new UiColor[0];

    private void rebuildRuns() {
        List<Integer> data = new ArrayList<>();
        List<UiColor> colors = new ArrayList<>();
        for (int row = 0; row < GRID; row++) {
            int col = 0;
            while (col < GRID) {
                int argb = pixels[row * GRID + col];
                if (argb == 0) { col++; continue; }
                int start = col;
                while (col < GRID && pixels[row * GRID + col] == argb) col++;
                data.add(start);
                data.add(row);
                data.add(col - start);
                colors.add(UiPixelGrid.color(argb));
            }
        }
        int[] flat = new int[data.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = data.get(i);
        runs = flat;
        runColors = colors.toArray(new UiColor[0]);
        hudBounds = null;
    }

    // Géométrie du dessin en jeu, gardée tant que rien ne bouge (fenêtre,
    // taille d'un pixel, dessin) : une croix ne se recalcule pas 200 fois par seconde.
    private float[][] hudBounds;
    private float hudCx, hudCy, hudCell;

    private void drawDrawing(UiRenderer renderer, float cx, float cy, float cell, boolean hud,
                             int vpWidth, int vpHeight) {
        UiColor[] colors = runColors;
        if (colors.length == 0) return;
        float[][] bounds = hud ? hudBounds : null;
        if (bounds == null || bounds.length != colors.length || hudCx != cx || hudCy != cy || hudCell != cell || !hud) {
            bounds = runBounds(cx, cy, cell);
            if (hud) {
                hudBounds = bounds;
                hudCx = cx;
                hudCy = cy;
                hudCell = cell;
            }
        }
        renderer.drawRoundedRectBatch(bounds, colors, 0f, vpWidth, vpHeight);
    }

    /** Les bandes en pixels écran : la case du milieu de la grille est centrée sur ({@code cx}, {@code cy}), bords calés sur des pixels entiers. */
    private float[][] runBounds(float cx, float cy, float cell) {
        int[] data = runs;
        float left = (float) Math.round(cx - GRID * cell / 2f);
        float top = (float) Math.round(cy + GRID * cell / 2f);
        float[][] bounds = new float[data.length / 3][];
        for (int i = 0; i < bounds.length; i++) {
            int col = data[i * 3], row = data[i * 3 + 1], length = data[i * 3 + 2];
            bounds[i] = new float[]{ left + col * cell, top - (row + 1) * cell, left + (col + length) * cell, top - row * cell };
        }
        return bounds;
    }

    // ── Aperçu dans l'écran de configuration ───────────────────────────────

    private static final UiColor PREVIEW_BACKGROUND = new UiColor(34, 38, 48, 255);

    @Override
    public boolean hasConfigPreview() {
        return true;
    }

    @Override
    public void drawConfigPreview(UiRenderer renderer, float x, float y, float boxSize, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + boxSize, y + boxSize, UiTheme.RADIUS_SM, PREVIEW_BACKGROUND,
            vpWidth, vpHeight);
        // Trois fois la taille en jeu, moins si le viseur ne tiendrait pas dans le cadre.
        float room = boxSize / 2f * 0.85f;
        float zoom = Math.max(0.25f, Math.min(3f, room / Math.max(1f, extent())));
        drawCrosshair(renderer, x + boxSize / 2f, y + boxSize / 2f, zoom, false, vpWidth, vpHeight);
    }

    // ── Import d'une image ─────────────────────────────────────────────────

    private void askImage() {
        SettingModal.request(new SettingModal("Importer une image", answers -> {
            importError = importImage(answers.get(0));
            if (importError == null) mode = 1;
            // La ligne d'erreur apparaît ou disparaît, le type a pu changer : l'écran se reconstruit.
            invalidateSettings();
        }, SettingModal.Step.text("Chemin de l'image", "C:\\Users\\...\\crosshair.png")));
    }

    /** @return {@code null} si le dessin a été remplacé, sinon la raison de l'échec (texte source français). */
    private String importImage(String typed) {
        String path = typed == null ? "" : typed.trim();
        // « Copier en tant que chemin » de Windows entoure le chemin de guillemets.
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.isEmpty()) return "Aucun chemin n'a été donné.";
        try {
            File file = new File(path);
            if (!file.isFile()) return "Image introuvable à cet emplacement.";
            BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image == null) return "Ce fichier n'est pas une image lisible.";
            int[] fitted = fit(image);
            boolean empty = true;
            for (int argb : fitted) {
                if (argb != 0) { empty = false; break; }
            }
            if (empty) return "L'image est entièrement transparente.";
            System.arraycopy(fitted, 0, pixels, 0, pixels.length);
            rebuildRuns();
            return null;
        } catch (Throwable t) {
            LauncherLog.err("[CrosshairModule] import de l'image « " + path + " » : " + t);
            return "Ce fichier n'est pas une image lisible.";
        }
    }

    /**
     * L'image ramenée à la grille : recopiée telle quelle et centrée si elle
     * tient, sinon réduite au plus proche voisin (pas de lissage : un viseur
     * est du pixel art). Les pixels presque transparents sont vidés.
     */
    private static int[] fit(BufferedImage image) {
        int iw = image.getWidth(), ih = image.getHeight();
        float shrink = Math.max(1f, Math.max(iw, ih) / (float) GRID);
        int ow = Math.max(1, Math.min(GRID, Math.round(iw / shrink)));
        int oh = Math.max(1, Math.min(GRID, Math.round(ih / shrink)));
        int ox = (GRID - ow) / 2, oy = (GRID - oh) / 2;
        int[] out = new int[GRID * GRID];
        for (int row = 0; row < oh; row++) {
            int sy = Math.min(ih - 1, (int) ((row + 0.5f) * ih / oh));
            for (int col = 0; col < ow; col++) {
                int sx = Math.min(iw - 1, (int) ((col + 0.5f) * iw / ow));
                int argb = image.getRGB(sx, sy);
                out[(oy + row) * GRID + ox + col] = (argb >>> 24) < 24 ? 0 : argb;
            }
        }
        return out;
    }

    // ── Persistance du dessin : « longueur:couleur » par suite de cases identiques ──

    private String encodePixels() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pixels.length) {
            int argb = pixels[i], start = i;
            while (i < pixels.length && pixels[i] == argb) i++;
            if (sb.length() > 0) sb.append(',');
            sb.append(i - start).append(':').append(Integer.toHexString(argb));
        }
        return sb.toString();
    }

    /** Une valeur illisible ou d'une autre taille de grille est ignorée : le dessin en place reste. */
    private void decodePixels(String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            int[] decoded = new int[pixels.length];
            int at = 0;
            for (String token : raw.split(",")) {
                int sep = token.indexOf(':');
                int count = Integer.parseInt(token.substring(0, sep));
                int argb = Integer.parseUnsignedInt(token.substring(sep + 1), 16);
                if (count <= 0 || at + count > decoded.length) return;
                java.util.Arrays.fill(decoded, at, at + count, argb);
                at += count;
            }
            if (at != decoded.length) return;
            System.arraycopy(decoded, 0, pixels, 0, pixels.length);
            rebuildRuns();
        } catch (RuntimeException malformed) {
            LauncherLog.err("[CrosshairModule] dessin sauvegardé illisible, ignoré : " + malformed);
        }
    }

    // ── Barre de rechargement, celle de vanilla ────────────────────────────

    /** Les deux textures de vanilla font 16 × 4 pixels de GUI. */
    private static final int BAR_W = 16, BAR_H = 4;
    private static final String SPRITE_DIR = "textures/gui/sprites/hud/";

    private static boolean spritesAttempted;
    private static BufferedImage backgroundSprite, progressSprite;
    private static boolean cooldownErrorLogged;

    private void drawCooldownBar(UiRenderer renderer, int vpWidth, int vpHeight) {
        float progress = attackCooldownProgress();
        // Comme vanilla (bytecode de Gui.renderCrosshair) : rien tant que
        // l'attaque est prête, la barre n'existe que pendant le rechargement.
        // -1 = pas de rechargement sur cette version, ou monde pas chargé.
        if (progress < 0f || progress >= 1f) return;
        ensureSprites();
        if (backgroundSprite == null || progressSprite == null) return;

        // La place de vanilla, calculée comme lui en pixels de GUI entiers :
        // x = largeur/2 - 8, y = hauteur/2 - 7 + 16 (y vers le BAS chez lui).
        int scale = ClientData.guiScale();
        if (scale <= 0) scale = 2;
        int guiW = (int) Math.ceil(vpWidth / (double) scale), guiH = (int) Math.ceil(vpHeight / (double) scale);
        int gx = guiW / 2 - 8, gy = guiH / 2 - 7 + 16;
        float x = gx * scale;
        float y = vpHeight - (gy + BAR_H) * scale; // coin BAS-gauche, y vers le haut

        renderer.drawIcon("crosshair-cooldown-bg-" + scale, scaled(backgroundSprite, BAR_W, scale),
            x, y, BAR_W * scale, BAR_H * scale, vpWidth, vpHeight);
        // Vanilla : (int)(progress * 17) pixels de la texture, de gauche à droite — recadrée, jamais étirée.
        int filled = Math.min(BAR_W, (int) (progress * 17f));
        if (filled <= 0) return;
        renderer.drawIcon("crosshair-cooldown-fg-" + scale + "-" + filled, scaled(progressSprite, filled, scale),
            x, y, filled * scale, BAR_H * scale, vpWidth, vpHeight);
    }

    // Les textures agrandies une fois par taille (17 recadrages au plus par
    // échelle de GUI) : agrandies au plus proche voisin ICI, pour que le
    // moteur les pose pixel pour pixel au lieu de les lisser en les étirant.
    private static final java.util.Map<String, BufferedImage> SCALED = new java.util.HashMap<>();

    private static BufferedImage scaled(BufferedImage sprite, int columns, int scale) {
        String key = System.identityHashCode(sprite) + ":" + columns + ":" + scale;
        BufferedImage cached = SCALED.get(key);
        if (cached != null) return cached;
        int sw = Math.min(columns, sprite.getWidth()), sh = Math.min(BAR_H, sprite.getHeight());
        BufferedImage out = new BufferedImage(sw * scale, sh * scale, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < sh * scale; py++) {
            for (int px = 0; px < sw * scale; px++) {
                out.setRGB(px, py, sprite.getRGB(px / scale, py / scale));
            }
        }
        SCALED.put(key, out);
        return out;
    }

    /** Chargement paresseux, une seule fois. Par le gestionnaire de ressources (pack actif), sinon depuis le jar du jeu. */
    private static void ensureSprites() {
        if (spritesAttempted) return;
        spritesAttempted = true;
        try {
            backgroundSprite = loadSprite("crosshair_attack_indicator_background.png");
            progressSprite = loadSprite("crosshair_attack_indicator_progress.png");
            if (backgroundSprite == null || progressSprite == null) {
                LauncherLog.err("[CrosshairModule] textures de la barre de rechargement introuvables — barre non dessinée");
            }
        } catch (Throwable t) {
            LauncherLog.err("[CrosshairModule] textures de la barre de rechargement : " + t);
        }
    }

    private static BufferedImage loadSprite(String name) throws Exception {
        byte[] bytes = ClientData.resourceBytes("minecraft", SPRITE_DIR + name);
        if (bytes != null) {
            BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) return image;
        }
        String path = "assets/minecraft/" + SPRITE_DIR + name;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(path) : null;
        if (in == null) in = CrosshairModule.class.getClassLoader().getResourceAsStream(path);
        if (in == null) return null;
        try (InputStream is = in) {
            return javax.imageio.ImageIO.read(is);
        }
    }

    /**
     * @return 0..1 (1 = attaque prête), ou {@code -1f} si le rechargement
     * n'est pas lisible (monde pas chargé) — l'appelant ne dessine alors rien.
     */
    private float attackCooldownProgress() {
        // Par le POINT D'ACCÈS, pas par l'objet joueur : c'est la liaison de la
        // version qui nomme la méthode (getAttackStrengthScale en 26.1.2,
        // getAttackCooldownProgress en 1.21.11). Tenir un LocalPlayer ici liait
        // le type 26.1.2 et faisait tomber tout le module ailleurs.
        try {
            return PlayerData.attackStrengthScale(0f, -1f);
        } catch (Throwable t) {
            if (!cooldownErrorLogged) {
                cooldownErrorLogged = true;
                LauncherLog.err("[CrosshairModule] attackCooldownProgress: " + t);
            }
            return -1f;
        }
    }
}
