package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.anim.UiStagger;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La grille des cartes de modules, posée dans une liste qui défile.
 *
 * <p>Tout passe par Taffy : sections, rangées, cartes, et les petits
 * contrôles ancrés aux bords de chaque carte (cœur, bande d'activation). Le
 * nombre de colonnes se déduit de la largeur disponible — deux au plus en
 * Détaillé, une seule si la fenêtre est trop étroite pour deux cartes
 * lisibles ; autant que possible en Grille d'icônes.
 *
 * <p>Rangées EXPLICITES plutôt qu'un {@code flex-wrap} : la moindre erreur
 * d'arrondi sur la somme des largeurs ferait passer une carte à la ligne, et
 * une grille de deux colonnes deviendrait une colonne sans prévenir.
 *
 * <p>Elle se souvient d'où était chaque carte : à la reconstruction suivante
 * (mise en favori, changement d'agencement), une carte déjà là GLISSE vers sa
 * nouvelle place au lieu de rejouer son entrée.
 */
public final class ModGrid {

    // ── Mesures (pixels de référence, voir UiTheme) ────────────────────────
    static float gap() { return UiTheme.px(UiTheme.Space.S16); }
    /** Carte Détaillé et Compacte : icône, nom et deux lignes de description, puis la bande. */
    static float cardHeight() { return UiTheme.px(132f); }
    /** Tuile de la Grille d'icônes : icône, nom, une ligne de description, puis la bande. */
    static float tileHeight() { return UiTheme.px(172f); }
    static float sectionHeaderHeight() { return UiTheme.px(UiTheme.Space.S32); }
    /** Largeur d'une carte Détaillé en dessous de laquelle on passe à une colonne. */
    static float minDetailedWidth() { return UiTheme.px(300f); }
    /** Taille visée d'une tuile en Grille d'icônes. */
    static float iconCellWidth() { return UiTheme.px(240f); }
    /**
     * Bande du bas, sur tous les agencements : elle dit l'état écrit
     * (« Activé » / « Désactivé ») et la bande ENTIÈRE allume ou éteint le
     * module (widget invisible apparié). Un groupe y compte ses modules actifs.
     */
    static float bandHeight() { return UiTheme.px(48f); }
    static float heartSize() { return UiTheme.px(18f); }
    /** Le cœur, au coin haut-droit de la carte. */
    static float heartInset() { return UiTheme.px(UiTheme.Space.S14); }

    private final Map<String, float[]> previousPositions = new HashMap<>();
    private final Map<String, float[]> positions = new HashMap<>();
    /** Carte dont le favori vient de basculer : elle reçoit l'éclat, une fois. */
    private String pendingPulseId;
    private final Runnable onFavoritesChanged;

    /** @param onFavoritesChanged la liste des favoris a changé : l'écran doit refaire la grille. */
    public ModGrid(Runnable onFavoritesChanged) {
        this.onFavoritesChanged = onFavoritesChanged;
    }

    /** Ce qui a été posé pour une carte : la carte et les boîtes de ses contrôles. */
    private static final class Slot {
        final GridEntry entry;
        final Box card;
        Box toggle, heart;

        Slot(GridEntry entry, Box card) {
            this.entry = entry;
            this.card = card;
        }
    }

    /**
     * Remplit {@code scroll} : titres de section (si {@code separated}) et
     * cartes. {@code originX} est le bord gauche de la grille à l'écran,
     * {@code areaWidth} sa largeur (barre de défilement déjà retirée).
     */
    public void build(UiScrollContainer scroll, float originX, float areaWidth,
               List<GridEntry> favorites, List<GridEntry> others, boolean separated, int layoutMode) {
        previousPositions.clear();
        previousPositions.putAll(positions);
        positions.clear();

        float gap = gap();
        int cols;
        float rowH;
        if (layoutMode == 1) {
            cols = 1;
            rowH = cardHeight();
        } else if (layoutMode == 2) {
            cols = UiLayout.columns(areaWidth, iconCellWidth(), gap, 1, 64);
            rowH = tileHeight();
        } else {
            cols = UiLayout.columns(areaWidth, minDetailedWidth(), gap, 1, 2);
            rowH = cardHeight();
        }
        float cardW = UiLayout.cellWidth(areaWidth, cols, gap);

        Box root = UiLayout.column().gap(gap).width(areaWidth);
        List<Slot> slots = new ArrayList<>();
        Box favHeader = null, otherHeader = null;
        if (separated) {
            favHeader = root.add(UiLayout.fixed(areaWidth, sectionHeaderHeight()));
            addRows(root, favorites, cols, cardW, rowH, layoutMode, slots);
            // Écart double entre une section et le titre suivant.
            otherHeader = root.add(UiLayout.fixed(areaWidth, sectionHeaderHeight()).margin(gap, 0f, 0f, 0f));
            addRows(root, others, cols, cardW, rowH, layoutMode, slots);
        } else {
            List<GridEntry> all = new ArrayList<>(favorites);
            all.addAll(others);
            addRows(root, all, cols, cardW, rowH, layoutMode, slots);
        }

        // Hauteur libre : Taffy déduit celle du contenu, la liste le replace
        // elle-même dans son cadre. Les rectangles rendus sont relatifs à la
        // grille : on y ajoute originX à la pose.
        UiLayout.Result result = UiLayout.solve(root, areaWidth, 0f);
        if (result == null) {
            LauncherLog.err("[ModGrid] grille non calculée — cartes non créées");
            return;
        }
        if (favHeader != null) addTitle(scroll, result, favHeader, "Favoris", "Le cœur les épingle ici et dans l'accès rapide", originX);
        if (otherHeader != null) addTitle(scroll, result, otherHeader, "Autres modules", null, originX);

        int indexInSection = 0;
        GridEntry firstOther = separated && !others.isEmpty() ? others.get(0) : null;
        for (Slot slot : slots) {
            if (slot.entry == firstOther) indexInSection = 0;
            emit(scroll, result, slot, originX, layoutMode, indexInSection++);
        }
        // Consommé : l'éclat ne joue qu'une fois.
        pendingPulseId = null;
    }

    private void addRows(Box root, List<GridEntry> entries, int cols, float cardW, float rowH, int layoutMode, List<Slot> out) {
        for (int start = 0; start < entries.size(); start += cols) {
            Box row = root.add(UiLayout.row().gap(gap()).height(rowH).rigid());
            for (int c = 0; c < cols && start + c < entries.size(); c++) {
                GridEntry entry = entries.get(start + c);
                Slot slot = new Slot(entry, row.add(UiLayout.fixed(cardW, rowH)));
                // Contrôles ancrés aux bords de la carte : changer sa taille ne demande rien d'autre.
                float heart = heartSize();
                slot.heart = slot.card.add(UiLayout.fixed(heart, heart)
                    .absolute(heartInset(), heartInset(), null, null));
                if (entry.hasToggle()) {
                    slot.toggle = slot.card.add(UiLayout.fixed(cardW, bandHeight()).absolute(null, null, 0f, 0f));
                }
                out.add(slot);
            }
        }
    }

    private static void addTitle(UiScrollContainer scroll, UiLayout.Result result, Box box, String label, String hint, float originX) {
        TaffyLayoutResult.Rect r = result.rect(box);
        if (r == null) return;
        SectionTitle title = new SectionTitle(Lang.tr(label), hint == null ? null : Lang.tr(hint));
        title.x = r.x + originX;
        title.y = r.y;
        title.w = r.w;
        title.h = r.h;
        scroll.add(title);
        // Un point sur la barre de défilement, qui mène à cette section.
        scroll.addSection(Lang.tr(label), title);
    }

    private void emit(UiScrollContainer scroll, UiLayout.Result result, Slot slot, float ox, int layoutMode, int index) {
        TaffyLayoutResult.Rect c = result.rect(slot.card);
        if (c == null) return;
        GridEntry entry = slot.entry;
        float cx = c.x + ox, cy = c.y;
        positions.put(entry.id, new float[]{ cx, cy });

        // Cascade d'entrée, en même temps que l'arrivée de l'écran (plus
        // d'attente : l'écran ne fait plus qu'un fondu court) ; elle repart
        // de zéro à chaque section.
        float enterDelay = UiStagger.delayFor(index, UiTheme.Motion.STAGGER_S, UiTheme.Motion.STAGGER_MAX_S);
        ModCard card = new ModCard(entry, layoutMode, enterDelay);
        card.x = cx; card.y = cy; card.w = c.w; card.h = c.h;
        // La carte d'abord (dessinée dessous), ses contrôles ensuite (par-dessus).
        scroll.add(card);
        applyReorder(card, entry.id, cx, cy);

        String id = entry.id;
        if (slot.heart != null) {
            TaffyLayoutResult.Rect h = result.rect(slot.heart);
            if (h != null) {
                UiToggle heart = new UiToggle(h.x + ox, h.y, h.w, h.h, entry.favorite.getAsBoolean(), v -> {
                    entry.setFavorite.accept(v);
                    pendingPulseId = id;
                    if (onFavoritesChanged != null) onFavoritesChanged.run();
                }).heartStyle().boundTo(entry.favorite);
                scroll.add(heart);
                card.pairFavorite(heart);
            }
        }
        if (slot.toggle != null) {
            TaffyLayoutResult.Rect t = result.rect(slot.toggle);
            if (t != null) {
                // Toute la bande est le clic ; la carte la dessine (style invisible).
                UiToggle toggle = new UiToggle(t.x + ox, t.y, t.w, t.h, entry.enabled.getAsBoolean(), entry.setEnabled::accept)
                    .invisibleStyle().boundTo(entry.enabled);
                scroll.add(toggle);
                card.pairToggle(toggle);
            }
        }
    }

    /**
     * Une carte qui existait déjà ne fait pas d'entrée mais un DÉPLACEMENT
     * depuis son ancienne place (au demi-pixel près, elle n'a pas bougé). La
     * carte dont le favori vient de basculer reçoit l'éclat : on voit laquelle
     * a changé.
     */
    private void applyReorder(ModCard card, String id, float x, float y) {
        float[] prev = previousPositions.get(id);
        if (prev != null) {
            card.markAlreadyPresent();
            float dx = prev[0] - x, dy = prev[1] - y;
            if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) card.startMove(dx, dy);
        }
        if (id.equals(pendingPulseId)) card.playPulse();
    }
}
