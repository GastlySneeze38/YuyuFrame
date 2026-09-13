package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Moteur d'aimantation de l'éditeur HUD — calcul PUR : ni dessin, ni entrée,
 * ni modèle. {@link UiHudBox} lui passe la position brute du glisser, il rend
 * la position aimantée, les guides à dessiner et les distances à afficher.
 *
 * <h2>Pourquoi une refonte (2026-09-13)</h2>
 *
 * Retour utilisateur : « il ne suggère pas assez, on finit par bouger les
 * éléments pixel par pixel à la main ». L'ancien aimant ne connaissait que le
 * centre de l'écran et les bords IDENTIQUES des autres boîtes (gauche-gauche,
 * haut-haut…), avec un seuil de 6 pixels ÉCRAN (1,5 pixel GUI à l'échelle 4),
 * le premier repère trouvé l'emportant même si un autre était plus proche, et
 * des positions flottantes jamais posées sur la grille de pixels GUI.
 *
 * <h2>Règles</h2>
 * <ul>
 *   <li>Toutes les distances sont exprimées en pixels GUI puis converties par
 *       l'échelle GUI entière : l'aimant a la même « force » à toute échelle.</li>
 *   <li>Par axe, le repère le PLUS PROCHE gagne ; celui déjà accroché garde la
 *       main tant qu'on reste dans une zone un peu plus large (hystérésis),
 *       sinon la boîte saute entre deux repères voisins.</li>
 *   <li>Un axe non aimanté est arrondi au pixel GUI. Un axe aimanté garde la
 *       valeur exacte du repère, même hors grille (une boîte de hauteur non
 *       entière), sinon l'alignement serait cassé d'une fraction.</li>
 * </ul>
 *
 * Repère moteur : pixels framebuffer, origine en BAS à gauche, Y vers le haut.
 */
public final class HudSnapEngine {

    private HudSnapEngine() {}

    /** Zone d'aimantation, en pixels GUI. */
    static final float SNAP_GUI = 5f;
    /** Zone de maintien d'un repère déjà accroché — voir l'hystérésis dans la javadoc de classe. */
    static final float HOLD_GUI = 8f;
    /** Au-delà de la zone d'aimantation, jusqu'ici, un repère est ANNONCÉ (guide pâle) sans aimanter. */
    static final float PREVIEW_GUI = 16f;
    /** Marge standard aux bords de l'écran, en pixels GUI. */
    static final float MARGIN_GUI = 4f;
    /** Écart standard entre deux éléments collés, en pixels GUI. */
    static final float GAP_GUI = 2f;
    /** Un écart plus grand que ça n'est pas proposé comme « espacement égal ». */
    static final float MAX_SPACING_GUI = 60f;

    /** Nature d'un repère — décide de la couleur de son guide. */
    public enum Kind { SCREEN, ELEMENT, VANILLA, SPACING }

    /** Un rectangle cible (autre élément HUD ou élément vanilla). */
    public static final class Rect {
        public final float x, y, w, h;
        public final Kind kind;
        public final String label;

        public Rect(float x, float y, float w, float h, Kind kind, String label) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.kind = kind; this.label = label;
        }

        float left() { return x; }
        float right() { return x + w; }
        float bottom() { return y; }
        float top() { return y + h; }
        float centerX() { return x + w / 2f; }
        float centerY() { return y + h / 2f; }
    }

    /** Segment de guide : vertical (x constant) ou horizontal (y constant). */
    public static final class Guide {
        public final boolean vertical;
        public final float pos, from, to;
        public final Kind kind;
        /** Repère proche mais pas encore accroché — dessiné pâle. */
        public final boolean preview;

        Guide(boolean vertical, float pos, float from, float to, Kind kind, boolean preview) {
            this.vertical = vertical; this.pos = pos;
            this.from = Math.min(from, to); this.to = Math.max(from, to);
            this.kind = kind; this.preview = preview;
        }
    }

    /** Distance mesurée entre la boîte et un voisin (ou un bord d'écran), en pixels GUI. */
    public static final class Measure {
        public final float x1, y1, x2, y2;
        public final int gui;

        Measure(float x1, float y1, float x2, float y2, int gui) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.gui = gui;
        }
    }

    public static final class Result {
        public float x, y;
        public String keyX, keyY;
        public final List<Guide> guides = new ArrayList<Guide>();
    }

    /** Un repère candidat sur UN axe. */
    private static final class Candidate {
        final String key;
        /** Déplacement à appliquer pour l'accrocher. */
        final float delta;
        /** Position du guide sur l'axe. */
        final float pos;
        final Kind kind;
        /** Cible, pour l'étendue du guide ; {@code null} = l'écran entier. */
        final Rect target;

        Candidate(String key, float delta, float pos, Kind kind, Rect target) {
            this.key = key; this.delta = delta; this.pos = pos; this.kind = kind; this.target = target;
        }
    }

    /**
     * Aimante une boîte de taille {@code w×h} proposée en {@code (rawX, rawY)}.
     *
     * @param scale pixels écran par pixel GUI (échelle GUI entière)
     * @param prevKeyX repère accroché à la frame précédente sur X, {@code null} si aucun
     * @param enabled {@code false} quand l'utilisateur maintient Alt : ni aimant
     *     ni guide, mais toujours l'arrondi au pixel GUI
     */
    public static Result snap(float rawX, float rawY, float w, float h, List<Rect> targets,
                              int fbW, int fbH, float scale, String prevKeyX, String prevKeyY,
                              boolean enabled) {
        Result r = new Result();
        float snap = SNAP_GUI * scale, hold = HOLD_GUI * scale, preview = PREVIEW_GUI * scale;

        List<Candidate> xs = new ArrayList<Candidate>();
        List<Candidate> ys = new ArrayList<Candidate>();
        if (enabled) {
            collectX(xs, rawX, rawY, w, h, targets, fbW, scale);
            collectY(ys, rawX, rawY, w, h, targets, fbH, scale);
        }

        Candidate bx = choose(xs, prevKeyX, snap, hold);
        Candidate by = choose(ys, prevKeyY, snap, hold);

        r.x = bx != null ? rawX + bx.delta : Math.round(rawX / scale) * scale;
        if (by != null) {
            r.y = rawY + by.delta;
        } else {
            // Grille GUI verticale : la GUI vanilla part du HAUT, ses lignes
            // de pixels sont donc à fbH − k·échelle, pas à k·échelle.
            float top = rawY + h;
            float guiTop = Math.round((fbH - top) / scale);
            r.y = fbH - guiTop * scale - h;
        }
        r.x = Math.max(0f, Math.min(fbW - w, r.x));
        r.y = Math.max(0f, Math.min(fbH - h, r.y));
        r.keyX = bx != null ? bx.key : null;
        r.keyY = by != null ? by.key : null;

        if (enabled) {
            addGuides(r, xs, bx, true, w, h, fbW, fbH, preview);
            addGuides(r, ys, by, false, w, h, fbW, fbH, preview);
        }
        return r;
    }

    private static void collectX(List<Candidate> out, float x, float y, float w, float h,
                                 List<Rect> targets, int fbW, float scale) {
        float l = x, c = x + w / 2f, rr = x + w;
        float m = MARGIN_GUI * scale, gap = GAP_GUI * scale;

        add(out, "screen.left", 0f - l, 0f, Kind.SCREEN, null);
        add(out, "screen.left.margin", m - l, m, Kind.SCREEN, null);
        add(out, "screen.right", fbW - rr, fbW, Kind.SCREEN, null);
        add(out, "screen.right.margin", (fbW - m) - rr, fbW - m, Kind.SCREEN, null);
        add(out, "screen.centerX", fbW / 2f - c, fbW / 2f, Kind.SCREEN, null);

        for (int i = 0; i < targets.size(); i++) {
            Rect t = targets.get(i);
            String k = "t" + i + ".";
            add(out, k + "L=L", t.left() - l, t.left(), t.kind, t);
            add(out, k + "R=R", t.right() - rr, t.right(), t.kind, t);
            add(out, k + "C=C", t.centerX() - c, t.centerX(), t.kind, t);
            // Bords OPPOSÉS : juxtaposer, collé ou avec l'écart standard.
            add(out, k + "L=R", t.right() - l, t.right(), t.kind, t);
            add(out, k + "L=R+gap", t.right() + gap - l, t.right() + gap, t.kind, t);
            add(out, k + "R=L", t.left() - rr, t.left(), t.kind, t);
            add(out, k + "R=L-gap", t.left() - gap - rr, t.left() - gap, t.kind, t);
        }

        // Espacement égal : reprendre un écart déjà présent entre deux cibles
        // qui se font face sur cet axe.
        for (float g : spacings(targets, true, scale)) {
            for (int i = 0; i < targets.size(); i++) {
                Rect t = targets.get(i);
                if (!overlaps(y, y + h, t.bottom(), t.top())) continue;
                add(out, "t" + i + ".spaceR" + g, t.right() + g - l, t.right() + g, Kind.SPACING, t);
                add(out, "t" + i + ".spaceL" + g, t.left() - g - rr, t.left() - g, Kind.SPACING, t);
            }
        }
    }

    private static void collectY(List<Candidate> out, float x, float y, float w, float h,
                                 List<Rect> targets, int fbH, float scale) {
        float b = y, c = y + h / 2f, t0 = y + h;
        float m = MARGIN_GUI * scale, gap = GAP_GUI * scale;

        add(out, "screen.bottom", 0f - b, 0f, Kind.SCREEN, null);
        add(out, "screen.bottom.margin", m - b, m, Kind.SCREEN, null);
        add(out, "screen.top", fbH - t0, fbH, Kind.SCREEN, null);
        add(out, "screen.top.margin", (fbH - m) - t0, fbH - m, Kind.SCREEN, null);
        add(out, "screen.centerY", fbH / 2f - c, fbH / 2f, Kind.SCREEN, null);

        for (int i = 0; i < targets.size(); i++) {
            Rect t = targets.get(i);
            String k = "t" + i + ".";
            add(out, k + "B=B", t.bottom() - b, t.bottom(), t.kind, t);
            add(out, k + "T=T", t.top() - t0, t.top(), t.kind, t);
            add(out, k + "C=C", t.centerY() - c, t.centerY(), t.kind, t);
            add(out, k + "B=T", t.top() - b, t.top(), t.kind, t);
            add(out, k + "B=T+gap", t.top() + gap - b, t.top() + gap, t.kind, t);
            add(out, k + "T=B", t.bottom() - t0, t.bottom(), t.kind, t);
            add(out, k + "T=B-gap", t.bottom() - gap - t0, t.bottom() - gap, t.kind, t);
        }

        for (float g : spacings(targets, false, scale)) {
            for (int i = 0; i < targets.size(); i++) {
                Rect t = targets.get(i);
                if (!overlaps(x, x + w, t.left(), t.right())) continue;
                add(out, "t" + i + ".spaceT" + g, t.top() + g - b, t.top() + g, Kind.SPACING, t);
                add(out, "t" + i + ".spaceB" + g, t.bottom() - g - t0, t.bottom() - g, Kind.SPACING, t);
            }
        }
    }

    private static void add(List<Candidate> out, String key, float delta, float pos, Kind kind, Rect target) {
        out.add(new Candidate(key, delta, pos, kind, target));
    }

    /**
     * Écarts existants entre deux cibles qui se font face sur l'axe demandé
     * (et se chevauchent sur l'autre), dédoublonnés au pixel près.
     */
    private static List<Float> spacings(List<Rect> targets, boolean horizontal, float scale) {
        List<Float> out = new ArrayList<Float>();
        float max = MAX_SPACING_GUI * scale;
        for (Rect a : targets) {
            for (Rect b : targets) {
                if (a == b) continue;
                float g;
                if (horizontal) {
                    if (!overlaps(a.bottom(), a.top(), b.bottom(), b.top())) continue;
                    g = b.left() - a.right();
                } else {
                    if (!overlaps(a.left(), a.right(), b.left(), b.right())) continue;
                    g = b.bottom() - a.top();
                }
                if (g <= 0.5f || g > max) continue;
                boolean dup = false;
                for (float e : out) if (Math.abs(e - g) < 1f) { dup = true; break; }
                if (!dup) out.add(g);
            }
        }
        return out;
    }

    private static boolean overlaps(float a0, float a1, float b0, float b1) {
        return a0 < b1 && b0 < a1;
    }

    /** Le plus proche sous le seuil ; le repère déjà accroché garde la main jusqu'à {@code hold}. */
    private static Candidate choose(List<Candidate> cands, String prevKey, float snap, float hold) {
        Candidate best = null;
        for (Candidate c : cands) {
            float d = Math.abs(c.delta);
            if (prevKey != null && prevKey.equals(c.key) && d <= hold) return c;
            if (d <= snap && (best == null || d < Math.abs(best.delta))) best = c;
        }
        return best;
    }

    /**
     * Guide du repère accroché, plus ceux qui COÏNCIDENT avec lui une fois la
     * boîte posée (plusieurs alignements simultanés), plus les repères
     * proches annoncés en pâle — au plus deux, les plus proches.
     */
    private static void addGuides(Result r, List<Candidate> cands, Candidate chosen, boolean vertical,
                                  float w, float h, int fbW, int fbH, float previewDist) {
        float applied = chosen != null ? chosen.delta : 0f;
        List<Candidate> previews = new ArrayList<Candidate>();
        for (Candidate c : cands) {
            float rest = Math.abs(c.delta - applied);
            if (chosen != null && rest < 0.5f) {
                r.guides.add(guide(r, c, vertical, w, h, fbW, fbH, false));
            } else if (rest <= previewDist) {
                previews.add(c);
            }
        }
        java.util.Collections.sort(previews, (a, b) ->
            Float.compare(Math.abs(a.delta - applied), Math.abs(b.delta - applied)));
        for (int i = 0; i < previews.size() && i < 2; i++) {
            r.guides.add(guide(r, previews.get(i), vertical, w, h, fbW, fbH, true));
        }
    }

    /** Étendue du guide : de la boîte à sa cible, ou tout l'écran pour un repère d'écran. */
    private static Guide guide(Result r, Candidate c, boolean vertical, float w, float h,
                               int fbW, int fbH, boolean preview) {
        if (c.target == null) {
            return vertical ? new Guide(true, c.pos, 0f, fbH, c.kind, preview)
                            : new Guide(false, c.pos, 0f, fbW, c.kind, preview);
        }
        Rect t = c.target;
        return vertical
            ? new Guide(true, c.pos, Math.min(r.y, t.bottom()), Math.max(r.y + h, t.top()), c.kind, preview)
            : new Guide(false, c.pos, Math.min(r.x, t.left()), Math.max(r.x + w, t.right()), c.kind, preview);
    }

    /**
     * Distances de la boîte à son plus proche voisin de chaque côté — autre
     * cible qui lui fait face, sinon le bord de l'écran — en pixels GUI.
     */
    public static List<Measure> measure(float x, float y, float w, float h, List<Rect> targets,
                                        int fbW, int fbH, float scale) {
        List<Measure> out = new ArrayList<Measure>();
        float cx = x + w / 2f, cy = y + h / 2f;

        float leftEdge = 0f, rightEdge = fbW, bottomEdge = 0f, topEdge = fbH;
        for (Rect t : targets) {
            if (overlaps(y, y + h, t.bottom(), t.top())) {
                if (t.right() <= x) leftEdge = Math.max(leftEdge, t.right());
                if (t.left() >= x + w) rightEdge = Math.min(rightEdge, t.left());
            }
            if (overlaps(x, x + w, t.left(), t.right())) {
                if (t.top() <= y) bottomEdge = Math.max(bottomEdge, t.top());
                if (t.bottom() >= y + h) topEdge = Math.min(topEdge, t.bottom());
            }
        }
        addMeasure(out, leftEdge, cy, x, cy, x - leftEdge, scale);
        addMeasure(out, x + w, cy, rightEdge, cy, rightEdge - (x + w), scale);
        addMeasure(out, cx, bottomEdge, cx, y, y - bottomEdge, scale);
        addMeasure(out, cx, y + h, cx, topEdge, topEdge - (y + h), scale);
        return out;
    }

    private static void addMeasure(List<Measure> out, float x1, float y1, float x2, float y2,
                                   float px, float scale) {
        if (px < 0.5f) return;
        out.add(new Measure(x1, y1, x2, y2, Math.round(px / scale)));
    }

    /**
     * Repères vanilla fixes, convertis dans le repère moteur. Géométrie relue
     * dans le bytecode 26.1.2 ({@code Gui}) — identique en 1.21.11 :
     * <ul>
     *   <li>hotbar 182×22 en ({@code guiWidth/2 − 91}, {@code guiHeight − 22}) — {@code extractItemHotbar} ;</li>
     *   <li>cases de main secondaire 29×24 à {@code guiHeight − 23}, de part et d'autre ;</li>
     *   <li>rangées de vie et de faim 81×9 à {@code guiHeight − 39} — {@code extractPlayerHealth}/{@code extractFood} ;</li>
     *   <li>curseur 15×15 en ({@code (guiWidth − 15)/2}, {@code (guiHeight − 15)/2}) — {@code extractCrosshair}.</li>
     * </ul>
     * Barre d'expérience et armure NON proposées : leur position dépend du
     * nombre de rangées de cœurs et d'une autre classe, pas vérifiées.
     *
     * @param guiSize dimensions GUI réelles, {@code null} = aucune cible vanilla
     */
    public static List<Rect> vanillaTargets(int[] guiSize, int fbH, float scale) {
        List<Rect> out = new ArrayList<Rect>();
        if (guiSize == null || scale <= 0f) return out;
        int gw = guiSize[0], gh = guiSize[1];
        int center = gw / 2;
        vanilla(out, center - 91, gh - 22, 182, 22, "Hotbar", fbH, scale);
        vanilla(out, center - 91 - 29, gh - 23, 29, 24, "Main secondaire", fbH, scale);
        vanilla(out, center + 91, gh - 23, 29, 24, "Main secondaire", fbH, scale);
        vanilla(out, center - 91, gh - 39, 81, 9, "Vie", fbH, scale);
        vanilla(out, center + 91 - 81, gh - 39, 81, 9, "Faim", fbH, scale);
        vanilla(out, (gw - 15) / 2, (gh - 15) / 2, 15, 15, "Curseur", fbH, scale);
        return out;
    }

    /** Rectangle GUI (origine haut-gauche) → repère moteur (origine bas-gauche). */
    private static void vanilla(List<Rect> out, int gx, int gy, int gw, int gh, String label, int fbH, float s) {
        out.add(new Rect(gx * s, fbH - (gy + gh) * s, gw * s, gh * s, Kind.VANILLA, label));
    }
}
