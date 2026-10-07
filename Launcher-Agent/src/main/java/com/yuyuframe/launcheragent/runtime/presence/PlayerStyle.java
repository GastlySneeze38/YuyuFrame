package com.yuyuframe.launcheragent.runtime.presence;

import java.util.UUID;

/**
 * L'apparence du pseudo d'un joueur YuyuFrame : le fond de son étiquette, le
 * logo qui le précède, la couleur de ses lettres. Réglée dans le module
 * « Joueur » ({@code runtime/module/visual/PlayerModule}).
 *
 * <p>Aucun type du jeu ni d'OpenGL ici : la géométrie sort par un
 * {@link Sink}, dans le repère d'une ligne de texte — x vers la droite, y vers
 * le bas, une unité par pixel de police, le texte occupant y de 0 à 8. La même
 * géométrie sert donc à l'étiquette au-dessus de la tête (posée dans le monde)
 * et à la liste Tab (posée à l'écran) : les deux se ressemblent parce qu'elles
 * sont dessinées par le même code.
 *
 * <p>Chaque sommet porte deux opacités : celle d'une étiquette cachée par un
 * mur (vanilla y montre le fond en entier et le texte à peine) et celle d'une
 * étiquette en vue.
 */
public final class PlayerStyle {

    private PlayerStyle() {
    }

    /** Reçoit des quadrilatères, quatre sommets à la suite. */
    public interface Sink {
        /** @param hidden  opacité (0-255) derrière un mur
         *  @param visible opacité (0-255) en vue */
        void vertex(float x, float y, int rgb, int hidden, int visible);

        /**
         * Les sommets qui suivent forment le panneau plein de l'étiquette
         * ({@code true}), ou reviennent au décor posé dessus ({@code false}).
         * Dans le monde, le panneau est opaque et cache ce qui est derrière
         * lui ; à l'écran il n'y a rien à cacher, d'où l'implémentation vide.
         */
        default void opaque(boolean on) {
        }
    }

    // ── Réglages, écrits par le module ───────────────────────────────────────

    public static final int PLATE_VANILLA = 0, PLATE_NEON = 1, PLATE_AURORA = 2, PLATE_GLASS = 3, PLATE_COMET = 4;
    public static final int TEXT_UNCHANGED = 0, TEXT_IF_UNCOLORED = 1, TEXT_ALWAYS = 2;
    public static final int ANIM_NONE = 0, ANIM_BREATH = 1, ANIM_WAVE = 2, ANIM_RAINBOW = 3;

    /** Faux tant que le module « Joueur » n'est pas enregistré ou qu'il est éteint. */
    public static volatile boolean active;
    public static volatile boolean logo = true;
    public static volatile int plate = PLATE_NEON;
    public static volatile int textMode = TEXT_IF_UNCOLORED;
    public static volatile int animation = ANIM_BREATH;
    public static volatile int colorA = 0xB43CFF;
    public static volatile int colorB = 0x28E0FF;
    public static volatile boolean tab = true;
    /** Styliser TOUS les joueurs : pour régler l'apparence sans avoir un second compte sous les yeux. */
    public static volatile boolean preview;
    /** Dessiner aussi l'étiquette de son propre joueur, en vue extérieure ; faux quand le module est éteint. */
    public static volatile boolean ownLabel;

    /** Largeur du logo et espace qui le sépare du pseudo, en pixels de police. */
    public static final float LOGO_WIDTH = 8.5F;
    public static final float LOGO_GAP = 2.0F;

    /**
     * Ce joueur doit-il être stylisé ? La question porte sur son PROFIL (UUID
     * et pseudo réels), jamais sur le texte affiché. Éteint, plus aucune
     * question ne part vers le serveur.
     */
    public static boolean applies(UUID id, String name) {
        if (!active || id == null || name == null) {
            return false;
        }
        return preview || NativePresence.query(id, name) == NativePresence.PRESENT;
    }

    private static final long START = System.nanoTime();

    /** Secondes écoulées, pour les animations. */
    public static float time() {
        return (System.nanoTime() - START) / 1_000_000_000.0F;
    }

    // ── Couleurs ─────────────────────────────────────────────────────────────

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    static int mix(int from, int to, float t) {
        if (t <= 0F) return from;
        if (t >= 1F) return to;
        int r = (int) ((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * t);
        int g = (int) ((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * t);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * t);
        return r << 16 | g << 8 | b;
    }

    private static int hue(float h) {
        h = (h - (float) Math.floor(h)) * 6F;
        float x = 1F - Math.abs(h % 2F - 1F);
        float r, g, b;
        if (h < 1F) { r = 1F; g = x; b = 0F; }
        else if (h < 2F) { r = x; g = 1F; b = 0F; }
        else if (h < 3F) { r = 0F; g = 1F; b = x; }
        else if (h < 4F) { r = 0F; g = x; b = 1F; }
        else if (h < 5F) { r = x; g = 0F; b = 1F; }
        else { r = 1F; g = 0F; b = x; }
        // Un peu de blanc : un arc-en-ciel saturé à fond se lit mal sur un fond sombre.
        return mix((int) (r * 255F) << 16 | (int) (g * 255F) << 8 | (int) (b * 255F), 0xFFFFFF, 0.25F);
    }

    /** Lent et jamais éteint : entre 0,7 et 1. */
    private static float breath(float time) {
        return 0.85F + 0.15F * (float) Math.sin(time * TWO_PI / 3.2F);
    }

    /**
     * Faut-il recolorer cette lettre ? Sur beaucoup de serveurs la couleur d'un
     * pseudo est une information de jeu (l'équipe, le grade) : par défaut on ne
     * touche qu'aux lettres que le serveur a laissées blanches.
     */
    public static boolean recolors(int serverRgb) {
        int mode = textMode;
        return mode == TEXT_ALWAYS || (mode == TEXT_IF_UNCOLORED && (serverRgb & 0xFFFFFF) == 0xFFFFFF);
    }

    /** Couleur d'une lettre placée à {@code t} (0 à gauche du pseudo, 1 à droite). */
    public static int textColor(float t, float time) {
        switch (animation) {
            case ANIM_BREATH:
                return mix(0x000000, mix(colorA, colorB, t), breath(time));
            case ANIM_WAVE:
                return mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * (t * 0.9F - time * 0.35F)));
            case ANIM_RAINBOW:
                return hue(t * 0.6F - time * 0.15F);
            default:
                return mix(colorA, colorB, t);
        }
    }

    // ── Géométrie ────────────────────────────────────────────────────────────

    private static void quad(Sink s, float x0, float y0, float x1, float y1, int left, int right, int hidden, int visible) {
        s.vertex(x0, y0, left, hidden, visible);
        s.vertex(x0, y1, left, hidden, visible);
        s.vertex(x1, y1, right, hidden, visible);
        s.vertex(x1, y0, right, hidden, visible);
    }

    /**
     * Une ligne brisée d'épaisseur constante, aux angles en onglet : chaque
     * tronçon s'arrête exactement où commence le suivant. Aucun recouvrement,
     * donc aucune surépaisseur ni tache plus claire aux coudes — c'est ce qui
     * rendait le premier tracé baveux.
     *
     * @param points x et y alternés, dans la grille du logo
     */
    private static void polyline(Sink s, float ox, float oy, float[] points, float half, int rgb, int hidden, int visible) {
        int count = points.length / 2;
        float previousLeftX = 0F, previousLeftY = 0F, previousRightX = 0F, previousRightY = 0F;
        for (int i = 0; i < count; i++) {
            float x = points[i * 2], y = points[i * 2 + 1];
            // Direction du tronçon qui arrive et de celui qui repart (les bouts n'en ont qu'une).
            float inX = 0F, inY = 0F, outX = 0F, outY = 0F;
            if (i > 0) {
                inX = x - points[i * 2 - 2];
                inY = y - points[i * 2 - 1];
                float length = (float) Math.sqrt(inX * inX + inY * inY);
                inX /= length;
                inY /= length;
            }
            if (i < count - 1) {
                outX = points[i * 2 + 2] - x;
                outY = points[i * 2 + 3] - y;
                float length = (float) Math.sqrt(outX * outX + outY * outY);
                outX /= length;
                outY /= length;
            }
            if (i == 0) {
                inX = outX;
                inY = outY;
            } else if (i == count - 1) {
                outX = inX;
                outY = inY;
            }
            // La bissectrice du coude, et de combien s'en écarter pour garder l'épaisseur.
            float normalX = -(inY + outY), normalY = inX + outX;
            float length = (float) Math.sqrt(normalX * normalX + normalY * normalY);
            normalX /= length;
            normalY /= length;
            float miter = half / Math.max(normalX * -inY + normalY * inX, 0.5F);
            float leftX = ox + x * K + normalX * miter, leftY = oy + y * K + normalY * miter;
            float rightX = ox + x * K - normalX * miter, rightY = oy + y * K - normalY * miter;
            if (i > 0) {
                s.vertex(previousLeftX, previousLeftY, rgb, hidden, visible);
                s.vertex(previousRightX, previousRightY, rgb, hidden, visible);
                s.vertex(rightX, rightY, rgb, hidden, visible);
                s.vertex(leftX, leftY, rgb, hidden, visible);
            }
            previousLeftX = leftX;
            previousLeftY = leftY;
            previousRightX = rightX;
            previousRightY = rightY;
        }
    }

    /**
     * Le haut, le bas et le coin coupé du fond en cours de dessin, posés par
     * {@link #plate} : l'étiquette au-dessus de la tête a de la place et
     * déborde du texte, une ligne de la liste Tab n'en a pas. Fil de rendu seulement.
     */
    private static float TOP = -2.0F, BOTTOM = 10.0F, CUT = 2.5F;

    /**
     * Le panneau de l'étiquette : à coins coupés (la silhouette du logo),
     * dégradé de gauche à droite. En vue il laisse deviner le décor derrière
     * lui (un cinquième de transparence) tout en cachant ce qui est dessiné
     * après lui — autres pseudos, vitres, eau : c'est sa profondeur qui les
     * arrête, pas son opacité. Derrière un mur il n'en reste qu'une ombre,
     * comme le fond de vanilla.
     */
    private static void panel(Sink s, float left, float right, int rgbLeft, int rgbRight) {
        float span = Math.max(right - left, 0.001F);
        int innerLeft = mix(rgbLeft, rgbRight, CUT / span);
        int innerRight = mix(rgbLeft, rgbRight, 1F - CUT / span);
        final int hidden = 64, visible = 205;
        s.opaque(true);
        s.vertex(left, TOP + CUT, rgbLeft, hidden, visible);
        s.vertex(left, BOTTOM - CUT, rgbLeft, hidden, visible);
        s.vertex(left + CUT, BOTTOM, innerLeft, hidden, visible);
        s.vertex(left + CUT, TOP, innerLeft, hidden, visible);
        quad(s, left + CUT, TOP, right - CUT, BOTTOM, innerLeft, innerRight, hidden, visible);
        s.vertex(right - CUT, TOP, innerRight, hidden, visible);
        s.vertex(right - CUT, BOTTOM, innerRight, hidden, visible);
        s.vertex(right, BOTTOM - CUT, rgbRight, hidden, visible);
        s.vertex(right, TOP + CUT, rgbRight, hidden, visible);
        s.opaque(false);
    }

    /** Un liseré horizontal aux couleurs du joueur, traversé par un éclat qui le parcourt. */
    private static void glowLine(Sink s, float x0, float x1, float y, float thickness, float time, float speed) {
        final int slices = 8;
        float head = time * speed;
        head -= (float) Math.floor(head);
        int previous = lineColor(0F, head);
        for (int i = 1; i <= slices; i++) {
            float t = i / (float) slices;
            int next = lineColor(t, head);
            quad(s, x0 + (x1 - x0) * (t - 1F / slices), y, x0 + (x1 - x0) * t, y + thickness, previous, next, 60, 255);
            previous = next;
        }
    }

    private static int lineColor(float t, float head) {
        float distance = Math.abs(t - head);
        distance = Math.min(distance, 1F - distance);
        return mix(mix(colorA, colorB, t), 0xFFFFFF, Math.max(0F, 1F - distance * 5F) * 0.85F);
    }

    /**
     * Le fond de l'étiquette, de {@code left} à {@code right} (bords du contenu,
     * marge comprise). {@link #PLATE_VANILLA} ne passe pas par ici : c'est le
     * rectangle noir de vanilla, que chaque appelant sait déjà dessiner.
     */
    public static void plate(Sink s, float left, float right, float top, float bottom, float time) {
        TOP = top;
        BOTTOM = bottom;
        CUT = Math.min(2.5F, (bottom - top) / 4.0F);
        switch (plate) {
            case PLATE_NEON: {
                // Panneau sombre, deux liserés lumineux qu'un éclat parcourt en sens inverse.
                panel(s, left, right, 0x0A0818, 0x0A0818);
                glowLine(s, left + CUT, right - CUT, TOP, 0.7F, time, 0.30F);
                glowLine(s, left + CUT, right - CUT, BOTTOM - 0.7F, 0.7F, -time, 0.30F);
                break;
            }
            case PLATE_AURORA: {
                // Les deux couleurs du joueur ondulent lentement d'un bord à l'autre,
                // assombries pour que le pseudo reste lisible dessus.
                float phase = time * 0.25F;
                int a = mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * phase));
                int b = mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * (phase + 0.45F)));
                panel(s, left, right, mix(a, 0x000000, 0.55F), mix(b, 0x000000, 0.55F));
                break;
            }
            case PLATE_GLASS: {
                // Verre fumé : un reflet en haut, une barre de couleur qui respire à chaque bout.
                panel(s, left, right, 0x14141C, 0x14141C);
                quad(s, left + CUT, TOP, right - CUT, TOP + 0.5F, 0xFFFFFF, 0xFFFFFF, 0, 80);
                quad(s, left + CUT, TOP + 0.5F, right - CUT, (TOP + BOTTOM) / 2.0F, 0xFFFFFF, 0xFFFFFF, 0, 32);
                int pulse = (int) (255F * breath(time));
                quad(s, left, TOP + CUT, left + 0.9F, BOTTOM - CUT, colorA, colorA, pulse / 2, pulse);
                quad(s, right - 0.9F, TOP + CUT, right, BOTTOM - CUT, colorB, colorB, pulse / 2, pulse);
                break;
            }
            case PLATE_COMET: {
                // Panneau sombre, souligné d'une comète qui file de gauche à droite.
                panel(s, left, right, 0x05050C, 0x0C0A1E);
                float x0 = left + CUT, x1 = right - CUT, y = BOTTOM - 1.0F;
                quad(s, x0, y, x1, y + 0.6F, colorA, colorB, 45, 90);
                float head = time * 0.45F;
                head -= (float) Math.floor(head);
                float tail = Math.max(head - 0.4F, 0F);
                float hx = x0 + (x1 - x0) * head, tx = x0 + (x1 - x0) * tail;
                int headColor = mix(mix(colorA, colorB, head), 0xFFFFFF, 0.7F);
                int tailColor = mix(colorA, colorB, tail);
                // La traîne s'efface vers l'arrière : opacité nulle à sa queue.
                s.vertex(tx, y - 0.2F, tailColor, 0, 0);
                s.vertex(tx, y + 0.8F, tailColor, 0, 0);
                s.vertex(hx, y + 0.8F, headColor, 130, 255);
                s.vertex(hx, y - 0.2F, headColor, 130, 255);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Le logo YuyuFrame, réduit à ses contours : quatre équerres aux coins
     * coupés — violettes à gauche, bleues à droite dans l'image d'origine, ici
     * aux deux couleurs du joueur —, la pointe de bulle en bas à droite et un
     * pixel dans chaque brèche. Tracé, pas image : net à toute distance.
     *
     * <p>Un seul trait par équerre, d'épaisseur constante, sans halo : à cette
     * taille, tout ce qui déborde du trait l'empâte. Les couleurs sont
     * éclaircies pour ressortir sur un panneau sombre, et respirent à peine.
     *
     * @param x bord gauche du logo ; il occupe {@link #LOGO_WIDTH} de large et la hauteur du texte
     */
    public static void logo(Sink s, float x, float time) {
        float lift = 0.10F * (float) Math.sin(time * TWO_PI / 3.2F);
        // Chaque moitié garde la couleur du joueur tant qu'elle se détache du
        // fond ; sinon elle est tirée vers le blanc (ou le noir sur un fond
        // clair) juste assez pour se lire. Une couleur sombre sur un panneau
        // sombre ne disparaît donc plus.
        int backdrop = backdrop(time);
        int left = mix(readable(colorA, backdrop), 0xFFFFFF, Math.max(lift, 0F));
        int right = mix(readable(colorB, backdrop), 0xFFFFFF, Math.max(lift, 0F));
        final float half = 0.68F, oy = -0.1F;
        final int hidden = 80, visible = 255;
        polyline(s, x, oy, TOP_LEFT, half, left, hidden, visible);
        polyline(s, x, oy, BOTTOM_LEFT, half, left, hidden, visible);
        polyline(s, x, oy, TOP_RIGHT, half, right, hidden, visible);
        polyline(s, x, oy, BOTTOM_RIGHT, half, right, hidden, visible);
        // Les pixels qui s'échappent, un dans chaque brèche : plus larges que le
        // trait des équerres (centré sur x = 0,6 et x = 9,4), pour se lire comme
        // un élément à part et non comme un bout de trait manquant.
        quad(s, x - 0.55F * K, oy + 4.05F * K, x + 1.75F * K, oy + 4.95F * K, left, left, hidden, visible);
        quad(s, x + 8.25F * K, oy + 4.05F * K, x + 10.55F * K, oy + 4.95F * K, right, right, hidden, visible);
    }

    /** Échelle du dessin (grille de 10 sur 9,6) vers la hauteur d'une ligne. */
    private static final float K = 0.84F;

    /** La couleur sur laquelle le logo est posé, selon le fond choisi. */
    private static int backdrop(float time) {
        switch (plate) {
            case PLATE_NEON:
                return 0x0A0818;
            case PLATE_AURORA:
                // Le bord gauche du panneau, là où est le logo (même calcul que dans plate).
                return mix(mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * time * 0.25F)), 0x000000, 0.55F);
            case PLATE_GLASS:
                return 0x14141C;
            case PLATE_COMET:
                return 0x05050C;
            default:
                // Le fond de vanilla : du noir à peine opaque, donc ce qu'il y a
                // derrière — on vise le cas le plus courant, sombre.
                return 0x000000;
        }
    }

    /** Luminance perçue d'une couleur, de 0 (noir) à 1 (blanc). */
    private static float luminance(int rgb) {
        return 0.2126F * channel(rgb >> 16 & 255) + 0.7152F * channel(rgb >> 8 & 255) + 0.0722F * channel(rgb & 255);
    }

    private static float channel(int value) {
        float c = value / 255F;
        return c <= 0.03928F ? c / 12.92F : (float) Math.pow((c + 0.055F) / 1.055F, 2.4F);
    }

    /** Rapport de contraste visé entre le logo et son fond : celui qu'on demande à un pictogramme. */
    private static final float MIN_CONTRAST = 4.0F;

    /**
     * {@code color} telle quelle si elle se détache assez de {@code backdrop},
     * sinon éclaircie (ou assombrie, sur un fond clair) par petits pas jusqu'à
     * ce que ce soit le cas.
     */
    static int readable(int color, int backdrop) {
        float behind = luminance(backdrop);
        int toward = behind < 0.4F ? 0xFFFFFF : 0x000000;
        int candidate = color;
        for (int step = 1; step <= 8; step++) {
            float front = luminance(candidate);
            float ratio = (Math.max(front, behind) + 0.05F) / (Math.min(front, behind) + 0.05F);
            if (ratio >= MIN_CONTRAST) {
                return candidate;
            }
            candidate = mix(color, toward, step / 8F);
        }
        return candidate;
    }

    // Les quatre équerres, dans la grille du logo. Une brèche franche en haut
    // et en bas (entre x = 4 et x = 6) sépare les deux moitiés ; la dernière
    // se prolonge par la pointe de bulle.
    private static final float[] TOP_LEFT = { 4.0F, 0.6F, 2.6F, 0.6F, 0.6F, 2.6F, 0.6F, 3.7F };
    private static final float[] BOTTOM_LEFT = { 0.6F, 5.3F, 0.6F, 6.4F, 2.6F, 8.4F, 4.0F, 8.4F };
    private static final float[] TOP_RIGHT = { 6.0F, 0.6F, 7.4F, 0.6F, 9.4F, 2.6F, 9.4F, 3.7F };
    private static final float[] BOTTOM_RIGHT = { 9.4F, 5.3F, 9.4F, 6.4F, 7.4F, 8.4F, 6.0F, 8.4F, 5.1F, 9.5F };
}
