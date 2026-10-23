package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiLighting;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiModel;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiShapes3D;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiView3D;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Les tuiles des rangs, en 3D : un carré aux coins arrondis, épais et
 * biseauté, habillé de la texture du bloc de son matériau (planches de chêne,
 * pierre taillée, fer, or, diamant, netherite, améthyste pour Champion).
 *
 * <p>La texture du bloc est lue dans le jeu, donc dans le pack de ressources
 * du joueur. Un bloc que la version n'a pas (netherite et améthyste en 1.8.9)
 * prend la texture de repli embarquée ({@code /textures/rank/}, tirée de
 * {@code design/rank-tiles/}).
 *
 * <p>Modèle et textures sont faits une fois ; une tuile se dessine ensuite
 * sous n'importe quel angle ({@link #draw}).
 */
public final class RankTiles {

    private RankTiles() {
    }

    /** Côté d'une tuile, en pixels du modèle (un bloc). */
    public static final float SIZE = 16f;
    private static final float DEPTH = 4.2f;
    private static final float RADIUS = 3.6f;
    private static final float BEVEL = 1.1f;
    private static final int SEGMENTS = 5;

    /** Fichier de repli embarqué de chaque rang, de Bois à Champion. */
    private static final String[] FALLBACK = { "bois", "pierre", "fer", "or", "diamant", "netherite", "champion" };

    /** Textures du jeu : chemin des versions récentes, puis celui de la 1.8.9 ({@code null} = pas de bloc). */
    private static final String[][] BLOCK = {
        { "textures/block/oak_planks.png", "textures/blocks/planks_oak.png" },
        { "textures/block/cobblestone.png", "textures/blocks/cobblestone.png" },
        { "textures/block/iron_block.png", "textures/blocks/iron_block.png" },
        { "textures/block/gold_block.png", "textures/blocks/gold_block.png" },
        { "textures/block/diamond_block.png", "textures/blocks/diamond_block.png" },
        { "textures/block/netherite_block.png", null },
        { "textures/block/amethyst_block.png", null },
    };

    /** Couleur de la tranche de chaque rang : le matériau dans l'ombre. */
    private static final int[] EDGE = { 0x4E3519, 0x3F3F44, 0x6E747C, 0x8A5C08, 0x156C68, 0x18151A, 0x5A1666 };
    /** Le liseré d'or de Champion, sur ses biseaux. */
    private static final int CHAMPION_RIM = 0xF7C948;
    private static final int CHAMPION = 6;

    private static final BufferedImage[] TEXTURES = new BufferedImage[7];
    private static final boolean[] LOADED = new boolean[7];
    private static final UiModel[] MODELS = new UiModel[7];

    /** Placement des zones dans la texture composée (voir {@link #compose}). */
    private static float[] faceUv(BufferedImage composed) {
        int w = composed.getWidth();
        int block = w - 2;
        return new float[]{ 0f, 0f, block / (float) w, 1f };
    }

    private static float[] sideUv(BufferedImage composed) {
        int w = composed.getWidth();
        return new float[]{ (w - 1.5f) / w, 0.5f };
    }

    private static float[] rimUv(BufferedImage composed) {
        int w = composed.getWidth();
        return new float[]{ (w - 0.5f) / w, 0.5f };
    }

    /**
     * La texture d'une tuile : le bloc, suivi d'une colonne de la couleur de
     * la tranche et d'une colonne du liseré. Une seule image par rang, donc un
     * seul appel de dessin par tuile.
     */
    public static BufferedImage compose(BufferedImage block, int edgeRgb, int rimRgb) {
        int w = block.getWidth(), h = block.getHeight();
        BufferedImage out = new BufferedImage(w + 2, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, block.getRGB(x, y) | 0xFF000000);
            out.setRGB(w, y, 0xFF000000 | edgeRgb);
            out.setRGB(w + 1, y, 0xFF000000 | rimRgb);
        }
        return out;
    }

    /**
     * Le modèle de tuile de ce rang (Champion a son liseré d'or). Un par rang :
     * ses coordonnées de texture dépendent de la largeur de la texture, qu'un
     * pack de ressources peut changer d'un bloc à l'autre.
     */
    public static UiModel model(int tier, BufferedImage composed) {
        tier = Math.max(0, Math.min(CHAMPION, tier));
        if (MODELS[tier] == null) {
            MODELS[tier] = UiShapes3D.roundedTile(SIZE, DEPTH, RADIUS, BEVEL, SEGMENTS,
                faceUv(composed), sideUv(composed), tier == CHAMPION ? rimUv(composed) : null);
        }
        return MODELS[tier];
    }

    /** La texture composée de ce rang, chargée au premier appel ; {@code null} si rien n'a pu être lu (journalisé). */
    public static BufferedImage texture(int tier) {
        tier = Math.max(0, Math.min(CHAMPION, tier));
        if (LOADED[tier]) return TEXTURES[tier];
        LOADED[tier] = true;
        BufferedImage block = gameBlock(tier);
        if (block == null) block = fallback(tier);
        if (block != null) {
            TEXTURES[tier] = compose(squareTop(block), EDGE[tier], tier == CHAMPION ? CHAMPION_RIM : lighter(EDGE[tier]));
        }
        return TEXTURES[tier];
    }

    /** Clé de la texture dans l'atlas du moteur. */
    public static String textureKey(int tier) {
        return "rank-tile-" + Math.max(0, Math.min(CHAMPION, tier));
    }

    /**
     * Dessine la tuile d'un rang, centrée sur {@code (cx, cy)}, de
     * {@code pixels} de côté face à l'écran. {@code yaw}/{@code pitch} en
     * radians ; {@code lighting} {@code null} = l'éclairage d'interface.
     */
    public static void draw(UiRenderer r, int tier, float cx, float cy, float pixels,
                            float yaw, float pitch, float roll, UiLighting lighting, float alpha, int vw, int vh) {
        BufferedImage tex = texture(tier);
        if (tex == null) return;
        UiModel model = model(tier, tex);
        UiView3D view = new UiView3D().center(cx, cy).scale(pixels / SIZE)
            .origin(0f, 0f, 0f).rotation(yaw, pitch, roll).eyeDistance(SIZE * 4.5f);
        r.drawModel(model, null, textureKey(tier), tex, view, lighting != null ? lighting : LIGHT, alpha, vw, vh);
    }

    /** Lumière des tuiles : d'en haut à gauche, un peu de face — le haut du biseau brille, le bas est dans l'ombre. */
    private static final UiLighting LIGHT = new UiLighting().ambient(0.5f, 0.5f, 0.52f)
        .directional(-0.45f, 0.8f, 0.75f, 1f, 0.98f, 0.94f, 0.72f);

    public static UiLighting defaultLighting() {
        return LIGHT;
    }

    // ── Chargement ────────────────────────────────────────────────────────

    private static BufferedImage gameBlock(int tier) {
        for (String path : BLOCK[tier]) {
            if (path == null) continue;
            try {
                byte[] bytes = ClientData.resourceBytes("minecraft", path);
                if (bytes != null) {
                    BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img != null) return img;
                }
            } catch (Throwable t) {
                LauncherLog.err("[RankTiles] texture du jeu " + path + " : " + t);
            }
        }
        return null;
    }

    private static BufferedImage fallback(int tier) {
        String resource = "/textures/rank/" + FALLBACK[tier] + ".png";
        try (InputStream in = RankTiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                LauncherLog.err("[RankTiles] repli absent du JAR : " + resource);
                return null;
            }
            return javax.imageio.ImageIO.read(in);
        } catch (Throwable t) {
            LauncherLog.err("[RankTiles] repli " + resource + " : " + t);
            return null;
        }
    }

    /** Une texture animée (plusieurs images empilées) ne garde que la première, carrée. */
    private static BufferedImage squareTop(BufferedImage img) {
        int w = img.getWidth();
        if (img.getHeight() <= w) return img;
        return img.getSubimage(0, 0, w, w);
    }

    private static int lighter(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 255) + 40);
        int g = Math.min(255, ((rgb >> 8) & 255) + 40);
        int b = Math.min(255, (rgb & 255) + 40);
        return (r << 16) | (g << 8) | b;
    }
}
