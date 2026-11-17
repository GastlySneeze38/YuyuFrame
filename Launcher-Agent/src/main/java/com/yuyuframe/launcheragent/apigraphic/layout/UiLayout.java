package com.yuyuframe.launcheragent.apigraphic.layout;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mise en page déclarative au-dessus de Taffy (refonte du 2026-11-17) : un
 * écran décrit sa STRUCTURE — « une colonne de 280 de large, puis un bloc qui
 * prend le reste » — et ne calcule plus aucune coordonnée. Chaque boîte peut
 * porter un widget ({@link Box#bind}) : après {@link #solve}, ses bornes sont
 * posées d'elles-mêmes.
 *
 * <pre>
 * Box root = UiLayout.row().size(w, h);
 * Box side = root.add(UiLayout.col().width(px(280)).pad(px(16)).gap(px(4)));
 * side.add(UiLayout.box().height(px(36)).bind(navHome));
 * root.add(UiLayout.col().grow(1).bind(content));
 * UiLayout.solve(root, w, h);
 * </pre>
 *
 * <p>Les tailles passées ici sont déjà à l'échelle (passer par
 * {@code UiTheme.px}) : cette classe ne connaît pas le thème.
 *
 * <p>Le repère rendu est celui du moteur (origine en BAS à gauche, Y qui
 * monte) : la conversion depuis Taffy est faite par {@link LayoutSolver}.
 *
 * <p>Le texte : Taffy ne sait pas mesurer une chaîne, {@link #text} le fait en
 * Java avant le calcul et donne à la boîte sa vraie taille.
 */
public final class UiLayout {
    private UiLayout() {}

    private static final AtomicLong IDS = new AtomicLong();

    /** Une boîte de l'arbre. Méthodes chaînables ; les tailles sont en pixels (déjà à l'échelle). */
    public static final class Box {
        final TaffyNode node;
        private final List<Box> children = new ArrayList<>();
        private UiWidget bound;

        Box(String id) {
            this.node = new TaffyNode(id == null ? "b" + IDS.incrementAndGet() : id);
        }

        public TaffyStyle style() { return node.style; }

        /** Ajoute un enfant et le rend (pour continuer à le décrire). */
        public Box add(Box child) {
            children.add(child);
            node.child(child.node);
            return child;
        }

        /** Ajoute plusieurs enfants et rend CETTE boîte. */
        public Box with(Box... kids) {
            for (Box k : kids) add(k);
            return this;
        }

        /** Le widget dont les bornes suivront cette boîte. */
        public Box bind(UiWidget widget) { this.bound = widget; return this; }

        public Box row() { node.style.flexDirection = "row"; return this; }
        public Box column() { node.style.flexDirection = "column"; return this; }

        public Box gap(float g) { node.style.gapRow = TaffyStyle.px(g); node.style.gapCol = TaffyStyle.px(g); return this; }
        public Box gap(float row, float col) { node.style.gapRow = TaffyStyle.px(row); node.style.gapCol = TaffyStyle.px(col); return this; }

        public Box pad(float all) { return pad(all, all, all, all); }
        public Box pad(float vertical, float horizontal) { return pad(vertical, horizontal, vertical, horizontal); }
        public Box pad(float top, float right, float bottom, float left) {
            node.style.padding = new String[]{ TaffyStyle.px(top), TaffyStyle.px(right), TaffyStyle.px(bottom), TaffyStyle.px(left) };
            return this;
        }

        public Box margin(float top, float right, float bottom, float left) {
            node.style.margin = new String[]{ TaffyStyle.px(top), TaffyStyle.px(right), TaffyStyle.px(bottom), TaffyStyle.px(left) };
            return this;
        }

        public Box width(float w) { node.style.width = TaffyStyle.px(w); return this; }
        public Box height(float h) { node.style.height = TaffyStyle.px(h); return this; }
        public Box size(float w, float h) { return width(w).height(h); }
        public Box widthPct(float pct) { node.style.width = TaffyStyle.pct(pct); return this; }
        public Box heightPct(float pct) { node.style.height = TaffyStyle.pct(pct); return this; }
        /** Occupe toute la place du parent sur les deux axes. */
        public Box fill() { return widthPct(100f).heightPct(100f); }

        public Box minWidth(float w) { node.style.minWidth = TaffyStyle.px(w); return this; }
        public Box maxWidth(float w) { node.style.maxWidth = TaffyStyle.px(w); return this; }
        public Box minHeight(float h) { node.style.minHeight = TaffyStyle.px(h); return this; }
        public Box maxHeight(float h) { node.style.maxHeight = TaffyStyle.px(h); return this; }

        public Box grow(float g) { node.style.flexGrow = g; return this; }
        public Box shrink(float s) { node.style.flexShrink = s; return this; }
        /** Ne rétrécit jamais (taille fixe tenue même quand la place manque). */
        public Box rigid() { node.style.flexShrink = 0f; return this; }
        public Box basis(float b) { node.style.flexBasis = TaffyStyle.px(b); return this; }

        public Box wrap() { node.style.flexWrap = "wrap"; return this; }

        /** {@code start}, {@code end}, {@code center}, {@code stretch}, {@code baseline}. */
        public Box alignItems(String v) { node.style.alignItems = v; return this; }
        /** {@code start}, {@code end}, {@code center}, {@code space-between}, {@code space-around}, {@code space-evenly}. */
        public Box justify(String v) { node.style.justifyContent = v; return this; }
        public Box center() { return alignItems("center").justify("center"); }

        /**
         * Hors du flux, ancrée aux bords du parent — {@code null} = bord libre.
         * Distances à un BORD (pas des positions) : aucune conversion d'axe.
         */
        public Box absolute(Float top, Float right, Float bottom, Float left) {
            node.style.position = "absolute";
            node.style.inset = new String[]{
                top == null ? TaffyStyle.AUTO : TaffyStyle.px(top),
                right == null ? TaffyStyle.AUTO : TaffyStyle.px(right),
                bottom == null ? TaffyStyle.AUTO : TaffyStyle.px(bottom),
                left == null ? TaffyStyle.AUTO : TaffyStyle.px(left) };
            return this;
        }

        /** Masquée : ni place ni bornes (le widget lié reçoit une taille nulle). */
        public Box hidden() { node.style.display = "none"; return this; }
    }

    /** Boîte neutre (rangée par défaut, comme en CSS). */
    public static Box box() { return new Box(null); }
    /** Boîte nommée — pour la retrouver dans le résultat sans garder sa référence. */
    public static Box box(String id) { return new Box(id); }
    public static Box row() { return box().row(); }
    public static Box column() { return box().column(); }
    /** Cale élastique : prend toute la place libre et pousse ce qui suit au bout. */
    public static Box spacer() { return box().grow(1f); }
    /** Boîte de taille fixe qui ne rétrécit pas. */
    public static Box fixed(float w, float h) { return box().size(w, h).rigid(); }

    /** Boîte à la taille exacte d'une ligne de texte. */
    public static Box text(UiFont font, String text, float scale) {
        UiFont f = font == null ? UiFont.REGULAR : font;
        float w = f.textWidth(text == null ? "" : text, scale);
        return fixed(w, lineHeight(f, scale));
    }

    /** Hauteur d'une ligne de texte de la police {@code font} à l'échelle {@code scale}. */
    public static float lineHeight(UiFont font, float scale) {
        UiFont f = font == null ? UiFont.REGULAR : font;
        return (f.ascent + f.descent) * scale * UiFont.SIZE_CORRECTION;
    }

    /**
     * Nombre de colonnes d'une grille : autant que la largeur en permet sans
     * descendre sous {@code minCell}, entre {@code minCols} et {@code maxCols}.
     */
    public static int columns(float availWidth, float minCell, float gap, int minCols, int maxCols) {
        int cols = (int) Math.floor((availWidth + gap) / (minCell + gap));
        return Math.max(minCols, Math.min(maxCols, cols));
    }

    /** Largeur d'une cellule pour {@code cols} colonnes séparées de {@code gap}. */
    public static float cellWidth(float availWidth, int cols, float gap) {
        return (availWidth - (cols - 1) * gap) / Math.max(1, cols);
    }

    /** Le résultat : rectangles dans le repère du moteur, déjà posés sur les widgets liés. */
    public static final class Result {
        private final LayoutSolver.Solved solved;

        Result(LayoutSolver.Solved solved) { this.solved = solved; }

        public TaffyLayoutResult.Rect rect(Box box) { return solved.get(box.node.id); }
        public TaffyLayoutResult.Rect rect(String id) { return solved.get(id); }
        public float width() { return solved.rootWidth; }
        public float height() { return solved.rootHeight; }
    }

    /**
     * Calcule l'arbre et pose les bornes de chaque widget lié.
     *
     * @param availHeight {@code <= 0} : hauteur libre, Taffy déduit celle du
     *                    contenu (une liste qui défile).
     * @return {@code null} si le calcul a échoué — déjà journalisé ; les
     *         widgets gardent alors leurs bornes précédentes.
     */
    public static Result solve(Box root, float availWidth, float availHeight) {
        return solve(root, availWidth, availHeight, 0f, 0f);
    }

    /** Même chose, avec un décalage ajouté à toutes les bornes (contenu calculé dans son propre repère). */
    public static Result solve(Box root, float availWidth, float availHeight, float offsetX, float offsetY) {
        LayoutSolver.Solved solved = LayoutSolver.solve(root.node, availWidth, availHeight);
        if (solved == null) return null;
        apply(root, solved, offsetX, offsetY);
        return new Result(solved);
    }

    private static void apply(Box box, LayoutSolver.Solved solved, float ox, float oy) {
        if (box.bound != null) {
            if (solved.has(box.node.id)) {
                solved.apply(box.node.id, box.bound, ox, oy);
            } else {
                // display:none — Taffy ne rend pas le nœud.
                box.bound.w = 0f;
                box.bound.h = 0f;
            }
        }
        for (Box child : box.children) apply(child, solved, ox, oy);
    }
}
