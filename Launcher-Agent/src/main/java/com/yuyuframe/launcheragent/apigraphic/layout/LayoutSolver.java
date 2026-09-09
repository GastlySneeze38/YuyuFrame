package com.yuyuframe.launcheragent.apigraphic.layout;

import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.Map;

/**
 * Passerelle entre {@link TaffyBridge} (moteur Flexbox/Grid Rust) et les
 * widgets de ce moteur — résout un arbre puis pose directement les bornes des
 * {@link UiWidget}.
 *
 * <p><b>LA raison d'être de cette classe : le sens de l'axe Y.</b> Taffy
 * raisonne comme CSS — origine en HAUT à gauche, Y qui descend. Tout ce moteur
 * raisonne en pixels framebuffer — origine en BAS à gauche, Y qui monte (même
 * repère que {@code gl_FragCoord}, voir {@code UiRenderer}/{@code
 * UiInputPoller}). Brancher Taffy sans cette conversion donne une interface
 * retournée verticalement, avec des bugs de clic parfaitement cohérents (le
 * hit-test utilise le même repère faux que le dessin, donc tout "marche" sauf
 * que c'est à l'envers). La conversion est faite ICI, une fois pour toutes,
 * plutôt que recopiée dans chaque écran :
 *
 * <pre>yMoteur = hauteurRacine - (yTaffy + hauteurNoeud)</pre>
 *
 * <p>La hauteur de référence est celle de la RACINE telle que Taffy l'a
 * effectivement résolue (pas la hauteur passée en paramètre) — indispensable
 * quand on résout un contenu de hauteur libre ({@code availH <= 0}, cas d'une
 * grille défilante dont la hauteur naturelle n'est connue qu'APRÈS calcul).
 * C'est pourquoi la racine DOIT porter un id.
 */
public final class LayoutSolver {
    private LayoutSolver() {}

    /** Résultat déjà converti dans le repère du moteur (Y montant) — voir {@link LayoutSolver}. */
    public static final class Solved {
        private final Map<String, TaffyLayoutResult.Rect> rects;
        /** Hauteur résolue de la racine — utile à l'appelant quand il a laissé Taffy déduire la hauteur naturelle du contenu. */
        public final float rootHeight;
        /** Largeur résolue de la racine. */
        public final float rootWidth;

        Solved(Map<String, TaffyLayoutResult.Rect> rects, float rootWidth, float rootHeight) {
            this.rects = rects;
            this.rootWidth = rootWidth;
            this.rootHeight = rootHeight;
        }

        /** @return le rect (repère moteur, Y montant) du nœud {@code id}, ou {@code null} si cet id n'est pas dans l'arbre résolu. */
        public TaffyLayoutResult.Rect get(String id) { return rects.get(id); }

        public boolean has(String id) { return rects.containsKey(id); }

        /**
         * Pose les bornes du widget depuis le nœud {@code id}. Sans effet (et
         * signalé) si l'id est absent — un widget laissé à ses bornes
         * d'origine reste visible et cliquable, ce qui est toujours préférable
         * à un widget téléporté en (0,0) par un rect nul.
         */
        public void apply(String id, UiWidget widget) {
            apply(id, widget, 0f, 0f);
        }

        /**
         * Variante décalée — pour un sous-arbre résolu dans son PROPRE repère
         * local qu'il faut replacer à l'écran (typiquement le contenu d'une
         * zone défilante, dont la grille est calculée à part pour que Taffy en
         * déduise la hauteur naturelle).
         */
        public void apply(String id, UiWidget widget, float offsetX, float offsetY) {
            TaffyLayoutResult.Rect r = rects.get(id);
            if (r == null) {
                LauncherLog.err("[LayoutSolver] noeud '" + id + "' absent du layout resolu — widget "
                    + widget.getClass().getSimpleName() + " laisse a ses bornes d'origine");
                return;
            }
            widget.x = r.x + offsetX;
            widget.y = r.y + offsetY;
            widget.w = r.w;
            widget.h = r.h;
        }
    }

    /**
     * Résout {@code root} et convertit tout le résultat dans le repère du
     * moteur.
     *
     * @param availW largeur disponible en pixels.
     * @param availH hauteur disponible, ou {@code <= 0} pour laisser Taffy
     *               déduire la hauteur naturelle du contenu (grille défilante).
     * @return {@code null} si le moteur natif est indisponible ou a échoué —
     *         l'échec est TOUJOURS journalisé (jamais avalé en silence),
     *         l'appelant doit prévoir un repli.
     */
    public static Solved solve(TaffyNode root, float availW, float availH) {
        if (root == null || root.id.isEmpty()) {
            LauncherLog.err("[LayoutSolver] la racine doit porter un id (elle sert de reference pour l'inversion de l'axe Y)");
            return null;
        }
        if (!TaffyBridge.ensureLoaded()) {
            LauncherLog.err("[LayoutSolver] content_core.dll indisponible — layout Taffy impossible");
            return null;
        }
        String json;
        try {
            json = TaffyBridge.computeLayout(root.toJson(), availW, availH);
        } catch (Throwable t) {
            LauncherLog.err("[LayoutSolver] computeLayout a leve: " + t);
            return null;
        }
        Map<String, TaffyLayoutResult.Rect> raw = TaffyLayoutResult.parse(json);
        if (raw == null) {
            LauncherLog.err("[LayoutSolver] Taffy a renvoye une erreur: " + TaffyLayoutResult.lastError);
            return null;
        }
        TaffyLayoutResult.Rect rootRect = raw.get(root.id);
        if (rootRect == null) {
            LauncherLog.err("[LayoutSolver] racine '" + root.id + "' absente du resultat — conversion Y impossible");
            return null;
        }
        float flipH = rootRect.h;
        Map<String, TaffyLayoutResult.Rect> flipped = new java.util.HashMap<>(raw.size() * 2);
        for (Map.Entry<String, TaffyLayoutResult.Rect> e : raw.entrySet()) {
            TaffyLayoutResult.Rect r = e.getValue();
            flipped.put(e.getKey(), new TaffyLayoutResult.Rect(r.x, flipH - (r.y + r.h), r.w, r.h));
        }
        return new Solved(flipped, rootRect.w, rootRect.h);
    }

    // ── Fabriques de nœuds courants ─────────────────────────────────────────
    //
    // Sucre syntaxique pour les formes qui reviennent dans chaque écran —
    // évite un bloc de 4 lignes par nœud au point d'appel.

    /** Boîte de taille fixe. */
    public static TaffyNode box(String id, float w, float h) {
        TaffyStyle s = new TaffyStyle();
        s.width = TaffyStyle.px(w);
        s.height = TaffyStyle.px(h);
        s.flexShrink = 0f;
        return new TaffyNode(id, s);
    }

    /** Colonne (empilement vertical) de hauteur libre, avec espacement entre enfants. */
    public static TaffyNode column(String id, float gap) {
        TaffyStyle s = new TaffyStyle();
        s.flexDirection = "column";
        s.gapRow = TaffyStyle.px(gap);
        return new TaffyNode(id, s);
    }

    /** Ligne (empilement horizontal) avec espacement entre enfants. */
    public static TaffyNode row(String id, float gap) {
        TaffyStyle s = new TaffyStyle();
        s.flexDirection = "row";
        s.gapCol = TaffyStyle.px(gap);
        return new TaffyNode(id, s);
    }

    /**
     * Cale élastique — occupe tout l'espace libre restant de son parent.
     * Anonyme (sans id) : purement structurel, jamais lu dans le résultat.
     * C'est ce qui "épingle" l'élément suivant au bord opposé d'une colonne
     * (ex: le bouton du bas d'une sidebar) sans calculer sa position.
     */
    public static TaffyNode spacer() {
        TaffyStyle s = new TaffyStyle();
        s.flexGrow = 1f;
        return new TaffyNode("", s);
    }

    /** Cale de taille FIXE — pour un écart ponctuel qui ne suit pas le {@code gap} régulier du parent. */
    public static TaffyNode gapBox(float size) {
        TaffyStyle s = new TaffyStyle();
        s.height = TaffyStyle.px(size);
        s.width = TaffyStyle.px(size);
        s.flexShrink = 0f;
        return new TaffyNode("", s);
    }

    /**
     * Enfant positionné librement DANS son parent, ancré à ses bords —
     * {@code Float.NaN} = bord non contraint. Sert aux petits contrôles posés
     * sur une carte (bascule en haut à droite, cœur en bas à droite) : leur
     * position découle des bords de la carte, plus d'arithmétique
     * {@code x + w - taille - marge} à refaire à chaque agencement.
     *
     * <p>Contrairement aux COORDONNÉES résolues (voir l'inversion de l'axe Y
     * en tête de classe), les insets ne demandent AUCUNE conversion : ce sont
     * des distances à un BORD, pas des positions sur un axe — "12 px du bord
     * haut" désigne le même bord visuel que Y descende ou monte. Ils se lisent
     * donc à l'identique des deux côtés du pont.
     */
    public static TaffyNode anchored(String id, float w, float h, Float top, Float right, Float bottom, Float left) {
        TaffyStyle s = new TaffyStyle();
        s.position = "absolute";
        s.width = TaffyStyle.px(w);
        s.height = TaffyStyle.px(h);
        s.inset = new String[]{
            top == null ? TaffyStyle.AUTO : TaffyStyle.px(top),
            right == null ? TaffyStyle.AUTO : TaffyStyle.px(right),
            bottom == null ? TaffyStyle.AUTO : TaffyStyle.px(bottom),
            left == null ? TaffyStyle.AUTO : TaffyStyle.px(left)
        };
        return new TaffyNode(id, s);
    }
}
