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

    /** Un trait épais de (ax, ay) à (bx, by). */
    private static void stroke(Sink s, float ax, float ay, float bx, float by, float half, int rgb, int hidden, int visible) {
        float dx = bx - ax, dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-4F) {
            return;
        }
        float nx = -dy / length * half, ny = dx / length * half;
        // Débordement d'une demi-épaisseur : deux traits qui se suivent se rejoignent sans trou.
        float ex = dx / length * half * 0.6F, ey = dy / length * half * 0.6F;
        s.vertex(ax - ex + nx, ay - ey + ny, rgb, hidden, visible);
        s.vertex(ax - ex - nx, ay - ey - ny, rgb, hidden, visible);
        s.vertex(bx + ex - nx, by + ey - ny, rgb, hidden, visible);
        s.vertex(bx + ex + nx, by + ey + ny, rgb, hidden, visible);
    }

    /**
     * Le haut, le bas et le coin coupé du fond en cours de dessin, posés par
     * {@link #plate} : l'étiquette au-dessus de la tête a de la place et
     * déborde du texte, une ligne de la liste Tab n'en a pas. Fil de rendu seulement.
     */
    private static float TOP = -2.0F, BOTTOM = 10.0F, CUT = 2.5F;

    /** Fond à coins coupés (la silhouette du logo), dégradé de gauche à droite. Derrière un mur comme en vue. */
    private static void fill(Sink s, float left, float right, int rgbLeft, int rgbRight, int alpha) {
        float span = Math.max(right - left, 0.001F);
        int innerLeft = mix(rgbLeft, rgbRight, CUT / span);
        int innerRight = mix(rgbLeft, rgbRight, 1F - CUT / span);
        // Le fond se voit autant derrière un mur qu'en vue, comme celui de vanilla :
        // il est dessiné une fois, dans la passe « cachée ».
        s.vertex(left, TOP + CUT, rgbLeft, alpha, 0);
        s.vertex(left, BOTTOM - CUT, rgbLeft, alpha, 0);
        s.vertex(left + CUT, BOTTOM, innerLeft, alpha, 0);
        s.vertex(left + CUT, TOP, innerLeft, alpha, 0);
        quad(s, left + CUT, TOP, right - CUT, BOTTOM, innerLeft, innerRight, alpha, 0);
        s.vertex(right - CUT, TOP, innerRight, alpha, 0);
        s.vertex(right - CUT, BOTTOM, innerRight, alpha, 0);
        s.vertex(right, BOTTOM - CUT, rgbRight, alpha, 0);
        s.vertex(right, TOP + CUT, rgbRight, alpha, 0);
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
            quad(s, x0 + (x1 - x0) * (t - 1F / slices), y, x0 + (x1 - x0) * t, y + thickness, previous, next, 120, 255);
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
                fill(s, left, right, 0x0A0818, 0x0A0818, 170);
                glowLine(s, left + CUT, right - CUT, TOP, 0.7F, time, 0.30F);
                glowLine(s, left + CUT, right - CUT, BOTTOM - 0.7F, 0.7F, -time, 0.30F);
                break;
            }
            case PLATE_AURORA: {
                // Les deux couleurs du joueur ondulent lentement d'un bord à l'autre.
                fill(s, left, right, 0x000000, 0x000000, 80);
                float phase = time * 0.25F;
                int a = mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * phase));
                int b = mix(colorA, colorB, 0.5F + 0.5F * (float) Math.sin(TWO_PI * (phase + 0.45F)));
                fill(s, left, right, a, b, 105);
                break;
            }
            case PLATE_GLASS: {
                // Verre fumé : un reflet en haut, une barre de couleur qui respire à chaque bout.
                fill(s, left, right, 0x000000, 0x000000, 95);
                quad(s, left + CUT, TOP, right - CUT, TOP + 0.5F, 0xFFFFFF, 0xFFFFFF, 70, 0);
                quad(s, left + CUT, TOP + 0.5F, right - CUT, (TOP + BOTTOM) / 2.0F, 0xFFFFFF, 0xFFFFFF, 30, 0);
                int pulse = (int) (255F * breath(time));
                quad(s, left, TOP + CUT, left + 0.9F, BOTTOM - CUT, colorA, colorA, pulse / 2, pulse);
                quad(s, right - 0.9F, TOP + CUT, right, BOTTOM - CUT, colorB, colorB, pulse / 2, pulse);
                break;
            }
            case PLATE_COMET: {
                // Panneau sombre, souligné d'une comète qui file de gauche à droite.
                fill(s, left, right, 0x05050C, 0x0C0A1E, 165);
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
     * <p>Dessiné deux fois, un halo large et pâle sous un trait fin et clair,
     * pour qu'il ressorte sur n'importe quel fond.
     *
     * @param x bord gauche du logo ; il occupe {@link #LOGO_WIDTH} de large et la hauteur du texte
     */
    public static void logo(Sink s, float x, float time) {
        float glow = breath(time);
        int haloLeft = colorA, haloRight = colorB;
        int coreLeft = mix(colorA, 0xFFFFFF, 0.45F), coreRight = mix(colorB, 0xFFFFFF, 0.45F);
        int halo = (int) (95F * glow);
        logoStrokes(s, x, 0.95F, haloLeft, haloRight, halo / 2, halo);
        logoStrokes(s, x, 0.42F, coreLeft, coreRight, 110, 255);
    }

    /** Échelle du dessin (grille de 10 sur 9,6) vers la hauteur d'une ligne. */
    private static final float K = 0.84F;

    private static void logoStrokes(Sink s, float ox, float half, int left, int right, int hidden, int visible) {
        float oy = -0.1F;
        // Équerre haut-gauche, puis bas-gauche.
        line(s, ox, oy, 4.2F, 0.6F, 2.6F, 0.6F, half, left, hidden, visible);
        line(s, ox, oy, 2.6F, 0.6F, 0.6F, 2.6F, half, left, hidden, visible);
        line(s, ox, oy, 0.6F, 2.6F, 0.6F, 3.8F, half, left, hidden, visible);
        line(s, ox, oy, 0.6F, 5.2F, 0.6F, 6.4F, half, left, hidden, visible);
        line(s, ox, oy, 0.6F, 6.4F, 2.6F, 8.4F, half, left, hidden, visible);
        line(s, ox, oy, 2.6F, 8.4F, 4.2F, 8.4F, half, left, hidden, visible);
        // Équerre haut-droite, puis bas-droite et sa pointe de bulle.
        line(s, ox, oy, 5.8F, 0.6F, 7.4F, 0.6F, half, right, hidden, visible);
        line(s, ox, oy, 7.4F, 0.6F, 9.4F, 2.6F, half, right, hidden, visible);
        line(s, ox, oy, 9.4F, 2.6F, 9.4F, 3.8F, half, right, hidden, visible);
        line(s, ox, oy, 9.4F, 5.2F, 9.4F, 6.4F, half, right, hidden, visible);
        line(s, ox, oy, 9.4F, 6.4F, 7.4F, 8.4F, half, right, hidden, visible);
        line(s, ox, oy, 7.4F, 8.4F, 5.8F, 8.4F, half, right, hidden, visible);
        line(s, ox, oy, 5.8F, 8.4F, 5.0F, 9.5F, half, right, hidden, visible);
        // Les pixels qui s'échappent, un dans chaque brèche.
        line(s, ox, oy, 0.2F, 4.5F, 1.0F, 4.5F, half, left, hidden, visible);
        line(s, ox, oy, 9.0F, 4.5F, 9.8F, 4.5F, half, right, hidden, visible);
    }

    private static void line(Sink s, float ox, float oy, float ax, float ay, float bx, float by, float half, int rgb, int hidden, int visible) {
        stroke(s, ox + ax * K, oy + ay * K, ox + bx * K, oy + by * K, half, rgb, hidden, visible);
    }
}
