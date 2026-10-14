package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * La page du module « Score PvP », en plein écran.
 *
 * <ol>
 *   <li>la barre du haut : retour, titre, icône d'information ({@link PvpInfoModal}) ;</li>
 *   <li>le bilan : la note moyenne en grand et en couleur, quatre chiffres
 *       (victoires, défaites, taux de victoire, meilleure note) et la forme
 *       récente — les dernières notes en barres, la référence en trait ;</li>
 *   <li>l'historique des <b>duels notés</b> du mode choisi dans la liste
 *       déroulante, une ligne par duel teintée par son résultat. Un clic sur
 *       une ligne ouvre la page du duel ({@link UiPvpDuelScreen}).</li>
 * </ol>
 *
 * <p>Tout entre en mouvement : les blocs du bilan montent en fondu l'un après
 * l'autre, les chiffres défilent jusqu'à leur valeur, les barres poussent,
 * les lignes glissent depuis la droite en cascade — et recommencent quand on
 * change de mode.
 *
 * <p>L'historique est demandé au noyau natif une fois, à l'ouverture
 * ({@link DuelHistory#load}) : il relit et renote les journaux, ce n'est pas
 * un travail à refaire à chaque image. Les notes et la moyenne viennent du
 * noyau ; ici, on ne fait que compter ce qui est affiché.
 */
public final class UiPvpScoreScreen extends UiScreenBase {

    private static final int GLASS_PASSES = 3;
    /** Nombre de notes dans le graphique de forme récente. */
    private static final int TREND = 20;

    private final Object lastScreen;
    private final DuelHistory history = DuelHistory.load();
    /** Le mode affiché ({@link PvpScoreUi#MODES}), et ses duels notés, du plus récent au plus ancien. */
    private int mode;
    private final List<DuelHistory.Duel> scored = new ArrayList<DuelHistory.Duel>();
    private int wins, losses, draws;
    private double best;

    private UiScrollContainer scroll;
    private PvpScoreUi.ModeDropdown modeDropdown;
    private PvpInfoModal info;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    /** Bas du bilan, haut de la liste. */
    private float heroBottom, listTop;

    /** Départ des animations de la page, et de celles de la liste (rejouées au changement de mode). */
    private final long openedAt = System.nanoTime();
    private long listShownAt = openedAt;

    public UiPvpScoreScreen(Object lastScreen) {
        super("Score PvP");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        selectMode(0);
    }

    /**
     * Les duels notés d'un mode, et ce qu'on en compte. Tous les duels notés
     * sont à la masse — le seul mode que le noyau note — donc le filtre ne
     * garde pour l'instant que la note ; le jour où l'historique dira le mode
     * de chaque duel, c'est ici qu'il se lira.
     */
    private void selectMode(int index) {
        mode = index;
        scored.clear();
        int w = 0, l = 0, d = 0;
        double top = -1;
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.score() < 0) continue;
            scored.add(duel);
            if (duel.result() == 1) w++;
            else if (duel.result() == 2) l++;
            else if (duel.result() == 3) d++;
            top = Math.max(top, duel.score());
        }
        wins = w; losses = l; draws = d;
        best = top;
        listShownAt = System.nanoTime();
    }

    // Le fond est dessiné par la page elle-même (tout l'écran en verre) : le
    // voile plat de la base passerait par-dessus le flou.
    @Override
    protected UiColor overlayColor() {
        return UiColor.TRANSPARENT;
    }

    @Override
    protected List<UiWidget> modalWidgets() {
        return info == null ? null : info.widgets();
    }

    @Override
    protected void onModalEscape() {
        if (info != null) info.close();
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastW || screenHeight != lastH || UiTheme.UI_SCALE != lastScale)) {
            buildLayout();
            lastW = screenWidth; lastH = screenHeight; lastScale = UiTheme.UI_SCALE;
        }
        if (info != null && info.closed()) info = null;
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        try {
            PvpScoreUi.drawPage(renderer, Lang.tr("SCORE PVP"), screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] fond : " + t);
        }
        super.uiDraw(mouseX, mouseY);
        try {
            float[] c = PvpScoreUi.content(screenWidth, screenHeight);
            drawHero(renderer, c[0], c[1], c[2] - UiTheme.scaled(24f));
            drawListTitle(renderer, c[0], c[1]);
            if (scored.isEmpty()) {
                PvpScoreUi.alpha = PvpScoreUi.ease(listShownAt, 0.3f, 0.5f);
                String empty = Lang.tr("Aucun duel noté pour l'instant : ils apparaîtront ici dès le premier.");
                PvpScoreUi.centered(renderer, UiFont.REGULAR, empty, screenWidth / 2f, (listTop + c[3]) / 2f, c[1] - c[0],
                    UiTheme.TEXT_MUTED, UiTheme.scaled(0.56f), screenWidth, screenHeight);
                PvpScoreUi.alpha = 1f;
            } else if (scroll != null) {
                scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
            // La liste des modes passe par-dessus l'historique.
            if (modeDropdown != null) modeDropdown.drawPanel(renderer, mouseX, mouseY, screenWidth, screenHeight);
            if (info != null) info.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] uiDraw: " + t);
        } finally {
            PvpScoreUi.alpha = 1f;
        }
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // La liste ne défile ni ne se clique tant que l'explication ou la liste des modes est ouverte.
        boolean blocked = info != null || (modeDropdown != null && modeDropdown.busy(input.mouseX, input.mouseY));
        if (scroll != null && !blocked) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(PvpScoreUi.backButton(screenWidth, screenHeight, () -> closeTo(lastScreen)));
        widgets.add(PvpScoreUi.helpButton(screenWidth, screenHeight,
            () -> info = new PvpInfoModal(null, PvpInfoModal.OVERVIEW)));

        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        heroBottom = c[2] - UiTheme.scaled(24f) - heroHeight();
        listTop = heroBottom - UiTheme.scaled(80f);

        float dropW = UiTheme.scaled(230f);
        float dropH = UiTheme.scaled(38f);
        // À gauche, juste au-dessus de la liste qu'elle filtre : c'est là que l'œil la cherche.
        modeDropdown = new PvpScoreUi.ModeDropdown(c[0], heroBottom - UiTheme.scaled(18f) - dropH, dropW, dropH, mode, index -> {
            selectMode(index);
            buildList();
        });
        widgets.add(modeDropdown);
        buildList();
    }

    /** Les lignes de l'historique, pour le mode choisi. */
    private void buildList() {
        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        float rowH = UiTheme.scaled(66f);
        float gap = UiTheme.scaled(8f);
        float vpW = c[1] - c[0];
        scroll = new UiScrollContainer(c[0], c[3], vpW, Math.max(1f, listTop - c[3]));
        float rowW = vpW - UiTheme.scaled(18f);
        for (int i = 0; i < scored.size(); i++) {
            DuelHistory.Duel duel = scored.get(i);
            scroll.add(new DuelRow(c[0], -(i * (rowH + gap)) - rowH, rowW, rowH, duel, i,
                () -> closeTo(new UiPvpDuelScreen(this, duel)), c[3], listTop));
        }
    }

    // ── Le bilan ─────────────────────────────────────────────────────────────

    private static float heroHeight() {
        return UiTheme.scaled(196f);
    }

    /** Un bloc qui entre : fondu, et montée depuis un peu plus bas. Rend le décalage vertical à appliquer. */
    private float enter(float delay) {
        float p = PvpScoreUi.ease(openedAt, delay, 0.5f);
        PvpScoreUi.alpha = p;
        return -(1f - p) * UiTheme.scaled(26f);
    }

    /** La moyenne, les quatre chiffres et la forme récente, côte à côte. */
    private void drawHero(UiRenderer r, float left, float right, float top) {
        float bottom = top - heroHeight();
        float gap = UiTheme.scaled(16f);
        int vw = screenWidth, vh = screenHeight;

        // La moyenne : elle défile jusqu'à sa valeur.
        float dy = enter(0.05f);
        float avgRight = left + UiTheme.scaled(300f);
        UiColor tone = PvpScoreUi.scoreColor(history.average);
        PvpScoreUi.tintedCard(r, left, bottom + dy, avgRight, top + dy, tone, 0.24f, vw, vh);
        float center = (left + avgRight) / 2f;
        float width = avgRight - left - UiTheme.scaled(24f);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("NOTE MOYENNE"), center, top + dy - UiTheme.scaled(34f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);
        String average = history.average >= 0
            ? String.valueOf(PvpScoreUi.count(history.average, PvpScoreUi.ease(openedAt, 0.15f, 1.1f)))
            : PvpScoreUi.NO_SCORE;
        PvpScoreUi.centered(r, UiFont.BOLD, average, center, bottom + dy + UiTheme.scaled(64f), width,
            tone, UiTheme.scaled(3.3f), vw, vh);
        String basis = history.scored + " " + Lang.tr(history.scored > 1 ? "duels notés" : "duel noté");
        PvpScoreUi.centered(r, UiFont.REGULAR, basis, center, bottom + dy + UiTheme.scaled(24f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);

        // Les quatre chiffres, deux par deux, l'un après l'autre.
        float tilesLeft = avgRight + gap;
        float tileW = UiTheme.scaled(190f);
        float tileH = (heroHeight() - gap) / 2f;
        int played = wins + losses + draws;
        float rateProgress = PvpScoreUi.ease(openedAt, 0.4f, 1f);
        String rate = played > 0 ? PvpScoreUi.count(100.0 * wins / played, rateProgress) + " %" : PvpScoreUi.NO_SCORE;
        drawTile(r, 0, tilesLeft, top - tileH, tilesLeft + tileW, top, wins, String.valueOf(wins), Lang.tr("VICTOIRES"), PvpScoreUi.GOOD);
        drawTile(r, 1, tilesLeft + tileW + gap, top - tileH, tilesLeft + 2 * tileW + gap, top, losses, null,
            Lang.tr("DÉFAITES"), PvpScoreUi.BAD);
        drawTile(r, 2, tilesLeft, bottom, tilesLeft + tileW, bottom + tileH, -1, rate, Lang.tr("TAUX DE VICTOIRE"),
            played > 0 ? (wins * 2 >= played ? PvpScoreUi.GOOD : PvpScoreUi.BAD) : PvpScoreUi.NEUTRAL);
        drawTile(r, 3, tilesLeft + tileW + gap, bottom, tilesLeft + 2 * tileW + gap, bottom + tileH, best,
            null, Lang.tr("MEILLEURE NOTE"), PvpScoreUi.scoreColor(best));

        // La forme récente : ce qui reste de la largeur.
        float trendLeft = tilesLeft + 2 * tileW + 2 * gap;
        if (right - trendLeft > UiTheme.scaled(220f)) {
            dy = enter(0.32f);
            drawTrend(r, trendLeft, bottom + dy, right, top + dy);
        }
        PvpScoreUi.alpha = 1f;
    }

    /**
     * Une case du bilan, qui entre à son tour. {@code value} positif défile
     * jusqu'à sa valeur ; sinon {@code text} est affiché tel quel.
     */
    private void drawTile(UiRenderer r, int index, float x1, float y1, float x2, float y2, double value, String text,
                          String label, UiColor tone) {
        float dy = enter(0.12f + 0.07f * index);
        y1 += dy;
        y2 += dy;
        PvpScoreUi.tintedCard(r, x1, y1, x2, y2, tone, 0.18f, screenWidth, screenHeight);
        float center = (x1 + x2) / 2f;
        float width = x2 - x1 - UiTheme.scaled(16f);
        String shown = value >= 0
            ? String.valueOf(PvpScoreUi.count(value, PvpScoreUi.ease(openedAt, 0.2f + 0.07f * index, 0.9f)))
            : text != null ? text : PvpScoreUi.NO_SCORE;
        PvpScoreUi.centered(r, UiFont.BOLD, shown, center, y1 + (y2 - y1) * 0.42f, width, tone, UiTheme.scaled(1.15f), screenWidth, screenHeight);
        PvpScoreUi.centered(r, UiFont.REGULAR, label, center, y1 + UiTheme.scaled(12f), width, UiTheme.TEXT_SECONDARY,
            UiTheme.scaled(0.36f), screenWidth, screenHeight);
    }

    /**
     * Les dernières notes en barres, de la plus ancienne à gauche à la plus
     * récente à droite, chacune dans sa couleur ; un trait marque 50, la
     * référence. Les barres poussent l'une après l'autre.
     */
    private void drawTrend(UiRenderer r, float left, float bottom, float right, float top) {
        int vw = screenWidth, vh = screenHeight;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = UiTheme.scaled(18f);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("FORME RÉCENTE"), left + pad, top - UiTheme.scaled(32f), right - left - pad * 2f,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);

        // Les notes, de la plus récente à la plus ancienne.
        int count = Math.min(TREND, scored.size());
        float chartTop = top - UiTheme.scaled(50f);
        float chartBottom = bottom + pad;
        float chartLeft = left + pad;
        float chartRight = right - pad;
        if (count == 0) {
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Pas encore de duel noté"), (left + right) / 2f, (chartTop + chartBottom) / 2f,
                chartRight - chartLeft, UiTheme.TEXT_MUTED, UiTheme.scaled(0.44f), vw, vh);
            return;
        }
        float slot = (chartRight - chartLeft) / TREND;
        float barW = Math.max(UiTheme.scaled(4f), slot * 0.6f);
        float height = chartTop - chartBottom;
        float reference = chartBottom + height * 0.5f;
        PvpScoreUi.rect(r, chartLeft, reference, chartRight, reference + Math.max(1f, UiTheme.scaled(1f)), 0f,
            new UiColor(255, 255, 255, 40), vw, vh);
        float alpha = PvpScoreUi.alpha;
        for (int i = 0; i < count; i++) {
            // Les barres se rangent à droite : la plus récente toujours au bord.
            double score = scored.get(count - 1 - i).score();
            float x = chartRight - (count - i) * slot + (slot - barW) / 2f;
            float grow = PvpScoreUi.pop(openedAt, 0.45f + 0.035f * i, 0.55f);
            float h = Math.max(UiTheme.scaled(3f), height * (float) (score / 100.0) * Math.max(0f, grow));
            UiColor tone = PvpScoreUi.scoreColor(score);
            r.drawGradientRect(x, chartBottom, x + barW, chartBottom + h, Math.min(barW / 2f, UiTheme.scaled(3f)),
                tone.multiplyAlpha(0.55f * alpha), tone.multiplyAlpha(alpha), vw, vh);
        }
    }

    /** Le titre de la liste, le choix du mode, et le nom de chaque colonne au-dessus des lignes. */
    private void drawListTitle(UiRenderer r, float left, float right) {
        int vw = screenWidth, vh = screenHeight;
        PvpScoreUi.alpha = PvpScoreUi.ease(openedAt, 0.3f, 0.5f);
        // Le titre suit la liste déroulante du mode, posée à gauche.
        float y = heroBottom - UiTheme.scaled(44f);
        float titleLeft = modeDropdown != null ? modeDropdown.x + modeDropdown.w + UiTheme.scaled(22f) : left;
        PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("HISTORIQUE DES DUELS NOTÉS"), titleLeft, y, UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.48f), vw, vh);
        if (!scored.isEmpty()) {
            float[] col = DuelRow.columns(left, right - left - UiTheme.scaled(18f));
            float small = UiTheme.scaled(0.34f);
            float hy = heroBottom - UiTheme.scaled(74f);
            UiColor muted = UiTheme.TEXT_MUTED;
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("NOTE"), col[0], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("RÉSULTAT"), col[1], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("ADVERSAIRE"), col[2], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("NIVEAU ADVERSE"), col[3], hy, muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("OPPORTUNITÉS"), col[4], hy, UiTheme.scaled(140f), muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("AU CONTACT"), col[5], hy, UiTheme.scaled(120f), muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("DATE"), col[6], hy, UiTheme.scaled(120f), muted, small, vw, vh);
        }
        PvpScoreUi.alpha = 1f;
    }

    /**
     * Un duel de l'historique, tout sur une ligne teintée par son résultat :
     * la note dans une pastille de sa couleur, le résultat, l'adversaire et
     * son niveau, les opportunités converties, le temps au contact, la date.
     *
     * <p>Elle entre en glissant depuis la droite, à son tour dans la cascade,
     * sa note défilant et la barre de niveau poussant ; au survol, elle se
     * décale un peu et s'éclaire. Elle se coupe elle-même au bord de la
     * liste : voir {@link #draw}.
     */
    private final class DuelRow extends UiWidget {

        private final DuelHistory.Duel duel;
        private final int index;
        private final Runnable open;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        /** Les bords de la liste, en pixels d'écran : rien de la ligne n'est dessiné au-delà. */
        private final float clipBottom, clipTop;

        DuelRow(float x, float y, float w, float h, DuelHistory.Duel duel, int index, Runnable open, float clipBottom, float clipTop) {
            super(x, y, w, h);
            this.duel = duel;
            this.index = index;
            this.open = open;
            this.clipBottom = clipBottom;
            this.clipTop = clipTop;
        }

        /**
         * Les colonnes d'une ligne de largeur {@code w} : les débuts de note,
         * résultat, adversaire et niveau adverse, puis les BORDS DROITS des
         * opportunités, du temps au contact et de la date.
         */
        static float[] columns(float x, float w) {
            float pad = UiTheme.scaled(20f);
            float right = x + w - pad;
            return new float[]{
                x + pad,
                x + pad + UiTheme.scaled(104f),
                x + Math.max(UiTheme.scaled(270f), w * 0.25f),
                x + Math.max(UiTheme.scaled(520f), w * 0.48f),
                right - UiTheme.scaled(330f),
                right - UiTheme.scaled(170f),
                right,
            };
        }

        /** La partie coupée d'une ligne ne se survole ni ne se clique : on ne clique pas ce qu'on ne voit pas. */
        @Override
        public boolean contains(double mx, double my) {
            return super.contains(mx, my) && my >= clipBottom && my <= clipTop;
        }

        /** Le départ de cette ligne dans la cascade : les premières visibles d'abord, la suite sans attendre. */
        private float delay() {
            return 0.32f + Math.min(index, 12) * 0.045f;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            float p = PvpScoreUi.ease(listShownAt, delay(), 0.5f);
            if (p <= 0.001f) return;
            PvpScoreUi.alpha = p;
            try {
                drawRow(r, mouseX, mouseY, vw, vh, p);
            } finally {
                PvpScoreUi.alpha = 1f;
            }
        }

        /**
         * Coupée net au bord de la liste : sur les versions récentes du jeu, la
         * liste ne découpe pas ce qui dépasse de son cadre. Le fond n'est
         * dessiné que sur sa partie à l'intérieur de la liste, coins carrés du
         * côté coupé ; le contenu (pastille, textes, barre) tient dans une
         * bande au milieu de la ligne et n'apparaît que si cette bande est
         * entière dans la liste.
         */
        private void drawRow(UiRenderer r, double mouseX, double mouseY, int vw, int vh, float p) {
            float visibleBottom = Math.max(y, clipBottom);
            float visibleTop = Math.min(y + h, clipTop);
            if (visibleTop - visibleBottom < 1f) return;
            boolean cutBottom = visibleBottom > y;
            boolean cutTop = visibleTop < y + h;

            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float lift = hover.get();
            // Glisse depuis la droite en entrant ; se décale un peu au survol.
            float x = this.x + (1f - p) * UiTheme.scaled(70f) + lift * UiTheme.scaled(6f);

            UiColor tone = PvpScoreUi.resultColor(duel.result());
            float radius = UiTheme.scaled(10f);
            float bottomRadius = cutBottom ? 0f : radius;
            float topRadius = cutTop ? 0f : radius;
            // Le fond prend la couleur du résultat en transparence, davantage au survol.
            // Ordre des rayons : bas-gauche, bas-droit, haut-gauche, haut-droit.
            float strength = 0.16f + 0.14f * lift;
            r.drawRoundedRect(x, visibleBottom, x + w, visibleTop, bottomRadius, bottomRadius, topRadius, topRadius,
                PvpScoreUi.a(UiTheme.CARD_BG), vw, vh);
            // Le dégradé n'a qu'un rayon pour ses quatre coins : coupé, il est
            // carré, et rentré d'un rayon sur les côtés pour ne pas dépasser
            // des coins arrondis qui restent.
            float tintInset = cutBottom || cutTop ? radius : 0f;
            r.drawGradientRect2D(x + tintInset, visibleBottom, x + w - tintInset, visibleTop, cutBottom || cutTop ? 0f : radius,
                PvpScoreUi.a(tone.withAlpha(strength)), PvpScoreUi.a(tone.withAlpha(strength * 0.2f)),
                PvpScoreUi.a(tone.withAlpha(strength)), PvpScoreUi.a(tone.withAlpha(strength * 0.2f)), vw, vh);
            float accent = UiTheme.scaled(5f) + lift * UiTheme.scaled(2f);
            r.drawRoundedRect(x, visibleBottom, x + accent, visibleTop, cutBottom ? 0f : accent / 2f, 0f,
                cutTop ? 0f : accent / 2f, 0f, PvpScoreUi.a(tone), vw, vh);

            float[] col = columns(x, w);
            float mid = y + h / 2f;
            float normal = UiTheme.scaled(0.6f);
            float small = UiTheme.scaled(0.4f);

            // La note, dans une pastille de sa couleur.
            UiColor scoreTone = PvpScoreUi.scoreColor(duel.score());
            float chipW = UiTheme.scaled(80f);
            float chipH = UiTheme.scaled(42f);
            float chipY = mid - chipH / 2f;
            // Le contenu entier, ou rien.
            if (chipY < clipBottom || chipY + chipH > clipTop) return;
            PvpScoreUi.rect(r, col[0], chipY, col[0] + chipW, chipY + chipH, UiTheme.scaled(8f),
                scoreTone.withAlpha(0.2f + 0.1f * lift), vw, vh);
            float strong = UiTheme.scaled(0.86f);
            String score = String.valueOf(PvpScoreUi.count(duel.score(), PvpScoreUi.ease(listShownAt, delay() + 0.1f, 0.8f)));
            PvpScoreUi.centered(r, UiFont.BOLD, score, col[0] + chipW / 2f, PvpScoreUi.middle(mid, strong),
                chipW, scoreTone, strong, vw, vh);

            // Le résultat, en capitales et dans sa couleur.
            float result = UiTheme.scaled(0.62f);
            PvpScoreUi.left(r, UiFont.BOLD, PvpScoreUi.resultText(duel.result()).toUpperCase(java.util.Locale.ROOT),
                col[1], PvpScoreUi.middle(mid, result), col[2] - col[1] - UiTheme.scaled(12f), tone, result, vw, vh);

            // L'adversaire.
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            PvpScoreUi.left(r, UiFont.BOLD, opponent, col[2], PvpScoreUi.middle(mid, normal), col[3] - col[2] - UiTheme.scaled(16f),
                UiTheme.TEXT_PRIMARY, normal, vw, vh);

            // Son niveau : la valeur et une petite barre qui pousse, dans sa couleur à lui.
            double level = duel.report[NativeCombat.REPORT_FAMILIES + NativeCombat.FAMILY_COUNT - 1];
            PvpScoreUi.text(r, UiFont.BOLD, String.valueOf(Math.round(level)), col[3], PvpScoreUi.middle(mid, normal), PvpScoreUi.FOE, normal, vw, vh);
            float barLeft = col[3] + UiTheme.scaled(48f);
            float barRight = Math.min(barLeft + UiTheme.scaled(110f), col[4] - UiTheme.scaled(120f));
            if (barRight > barLeft + UiTheme.scaled(20f)) {
                float grow = PvpScoreUi.ease(listShownAt, delay() + 0.15f, 0.7f);
                PvpScoreUi.bar(r, barLeft, mid - UiTheme.scaled(3f), barRight, UiTheme.scaled(6f), level * grow, PvpScoreUi.FOE, vw, vh);
            }

            // Opportunités converties, temps au contact, date.
            String converted = (int) duel.report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) duel.report[NativeCombat.REPORT_MY_OPENED];
            PvpScoreUi.right(r, UiFont.BOLD, converted, col[4], PvpScoreUi.middle(mid, normal), UiTheme.scaled(140f),
                UiTheme.TEXT_PRIMARY, normal, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.duration(duel.report[NativeCombat.REPORT_ENGAGED_SECS]), col[5],
                PvpScoreUi.middle(mid, normal), UiTheme.scaled(130f), UiTheme.TEXT_SECONDARY, normal, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.date(duel.time), col[6], PvpScoreUi.middle(mid, small * 1.2f),
                UiTheme.scaled(140f), UiTheme.TEXT_SECONDARY, small * 1.2f, vw, vh);
        }

        @Override
        public void onClick() {
            open.run();
        }
    }
}
