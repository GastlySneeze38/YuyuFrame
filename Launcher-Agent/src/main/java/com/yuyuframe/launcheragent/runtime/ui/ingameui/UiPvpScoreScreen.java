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

import java.util.List;

/**
 * La page du module « Score PvP », en plein écran.
 *
 * <ol>
 *   <li>la barre du haut : retour, titre, « Comment ça marche ? » ({@link PvpInfoModal}) ;</li>
 *   <li>le bilan : la note moyenne en grand et en couleur, quatre chiffres
 *       (victoires, défaites, taux de victoire, meilleure note) et la forme
 *       récente — les dernières notes en barres, la référence en trait ;</li>
 *   <li>l'historique, une ligne par duel teintée par son résultat. Un clic
 *       sur une ligne ouvre la page du duel ({@link UiPvpDuelScreen}).</li>
 * </ol>
 *
 * <p>L'historique est demandé au noyau natif une fois, à l'ouverture
 * ({@link DuelHistory#load}) : il relit et renote les journaux, ce n'est pas
 * un travail à refaire à chaque image. Les notes et la moyenne viennent du
 * noyau ; ici, on ne fait que compter ce qui est affiché (victoires, défaites,
 * meilleure note, moyenne des derniers duels).
 */
public final class UiPvpScoreScreen extends UiScreenBase {

    private static final int GLASS_PASSES = 3;
    /** Nombre de notes dans le graphique de forme récente. */
    private static final int TREND = 20;
    /** Nombre de duels de la « moyenne récente ». */
    private static final int RECENT = 5;

    private final Object lastScreen;
    private final DuelHistory history = DuelHistory.load();
    private final int wins, losses, draws;
    private final double best, recentAverage;
    private UiScrollContainer scroll;
    private PvpInfoModal info;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    /** Bas du bilan, haut de la liste. */
    private float heroBottom;

    public UiPvpScoreScreen(Object lastScreen) {
        super("Score PvP");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        int w = 0, l = 0, d = 0, recentCount = 0;
        double top = -1, recentSum = 0;
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.result() == 1) w++;
            else if (duel.result() == 2) l++;
            else if (duel.result() == 3) d++;
            if (duel.score() >= 0) {
                top = Math.max(top, duel.score());
                if (recentCount < RECENT) {
                    recentSum += duel.score();
                    recentCount++;
                }
            }
        }
        wins = w; losses = l; draws = d;
        best = top;
        recentAverage = recentCount > 0 ? recentSum / recentCount : -1;
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
        info = null;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastW || screenHeight != lastH || UiTheme.UI_SCALE != lastScale)) {
            buildLayout();
            lastW = screenWidth; lastH = screenHeight; lastScale = UiTheme.UI_SCALE;
        }
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
            if (history.duels.isEmpty()) {
                String empty = Lang.tr("Aucun duel pour l'instant : ils apparaîtront ici dès le premier terminé.");
                PvpScoreUi.centered(renderer, UiFont.REGULAR, empty, screenWidth / 2f, (heroBottom + c[3]) / 2f, c[1] - c[0],
                    UiTheme.TEXT_MUTED, UiTheme.scaled(0.56f), screenWidth, screenHeight);
            } else if (scroll != null) {
                scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
            if (info != null) info.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] uiDraw: " + t);
        }
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // La liste ne défile plus tant que l'explication est ouverte.
        if (scroll != null && info == null) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(PvpScoreUi.backButton(screenWidth, screenHeight, () -> closeTo(lastScreen)));
        widgets.add(PvpScoreUi.helpButton(screenWidth, screenHeight,
            () -> info = new PvpInfoModal(null, PvpInfoModal.OVERVIEW, () -> info = null)));

        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        heroBottom = c[2] - UiTheme.scaled(24f) - heroHeight();
        float listTop = heroBottom - UiTheme.scaled(76f);
        float rowH = UiTheme.scaled(66f);
        float gap = UiTheme.scaled(8f);
        float vpW = c[1] - c[0];
        scroll = new UiScrollContainer(c[0], c[3], vpW, Math.max(1f, listTop - c[3]));
        float rowW = vpW - UiTheme.scaled(18f);
        for (int i = 0; i < history.duels.size(); i++) {
            DuelHistory.Duel duel = history.duels.get(i);
            scroll.add(new DuelRow(c[0], -(i * (rowH + gap)) - rowH, rowW, rowH, duel,
                () -> closeTo(new UiPvpDuelScreen(this, duel)), c[3], listTop));
        }
    }

    // ── Le bilan ─────────────────────────────────────────────────────────────

    private static float heroHeight() {
        return UiTheme.scaled(196f);
    }

    /** La moyenne, les quatre chiffres et la forme récente, côte à côte. */
    private void drawHero(UiRenderer r, float left, float right, float top) {
        float bottom = top - heroHeight();
        float gap = UiTheme.scaled(16f);
        int vw = screenWidth, vh = screenHeight;

        // La moyenne.
        float avgRight = left + UiTheme.scaled(300f);
        UiColor tone = PvpScoreUi.scoreColor(history.average);
        PvpScoreUi.tintedCard(r, left, bottom, avgRight, top, tone, 0.24f, vw, vh);
        float center = (left + avgRight) / 2f;
        float width = avgRight - left - UiTheme.scaled(24f);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("NOTE MOYENNE"), center, top - UiTheme.scaled(34f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);
        PvpScoreUi.centered(r, UiFont.BOLD, PvpScoreUi.scoreText(history.average), center, bottom + UiTheme.scaled(64f), width,
            tone, UiTheme.scaled(3.3f), vw, vh);
        String basis = history.scored + " " + Lang.tr(history.scored > 1 ? "duels notés" : "duel noté");
        PvpScoreUi.centered(r, UiFont.REGULAR, basis, center, bottom + UiTheme.scaled(24f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);

        // Les quatre chiffres, deux par deux.
        float tilesLeft = avgRight + gap;
        float tileW = UiTheme.scaled(190f);
        float tileH = (heroHeight() - gap) / 2f;
        int played = wins + losses + draws;
        String rate = played > 0 ? Math.round(100.0 * wins / played) + " %" : PvpScoreUi.NO_SCORE;
        drawTile(r, tilesLeft, top - tileH, tilesLeft + tileW, top, String.valueOf(wins), Lang.tr("VICTOIRES"), PvpScoreUi.GOOD);
        drawTile(r, tilesLeft + tileW + gap, top - tileH, tilesLeft + 2 * tileW + gap, top, String.valueOf(losses),
            Lang.tr("DÉFAITES"), PvpScoreUi.BAD);
        drawTile(r, tilesLeft, bottom, tilesLeft + tileW, bottom + tileH, rate, Lang.tr("TAUX DE VICTOIRE"),
            played > 0 ? (wins * 2 >= played ? PvpScoreUi.GOOD : PvpScoreUi.BAD) : PvpScoreUi.NEUTRAL);
        drawTile(r, tilesLeft + tileW + gap, bottom, tilesLeft + 2 * tileW + gap, bottom + tileH,
            PvpScoreUi.scoreText(best), Lang.tr("MEILLEURE NOTE"), PvpScoreUi.scoreColor(best));

        // La forme récente : ce qui reste de la largeur.
        float trendLeft = tilesLeft + 2 * tileW + 2 * gap;
        if (right - trendLeft > UiTheme.scaled(220f)) {
            drawTrend(r, trendLeft, bottom, right, top);
        }
    }

    private void drawTile(UiRenderer r, float x1, float y1, float x2, float y2, String value, String label, UiColor tone) {
        PvpScoreUi.tintedCard(r, x1, y1, x2, y2, tone, 0.18f, screenWidth, screenHeight);
        float center = (x1 + x2) / 2f;
        float width = x2 - x1 - UiTheme.scaled(16f);
        PvpScoreUi.centered(r, UiFont.BOLD, value, center, y1 + (y2 - y1) * 0.42f, width, tone, UiTheme.scaled(1.15f), screenWidth, screenHeight);
        PvpScoreUi.centered(r, UiFont.REGULAR, label, center, y1 + UiTheme.scaled(12f), width, UiTheme.TEXT_SECONDARY,
            UiTheme.scaled(0.36f), screenWidth, screenHeight);
    }

    /**
     * Les dernières notes en barres, de la plus ancienne à gauche à la plus
     * récente à droite, chacune dans sa couleur ; un trait marque 50, la
     * référence.
     */
    private void drawTrend(UiRenderer r, float left, float bottom, float right, float top) {
        int vw = screenWidth, vh = screenHeight;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = UiTheme.scaled(18f);
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("FORME RÉCENTE"), left + pad, top - UiTheme.scaled(32f), (right - left) / 2f,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);
        if (recentAverage >= 0) {
            String recent = Lang.tr("Moyenne des") + " " + RECENT + " " + Lang.tr("derniers :") + " ";
            String value = PvpScoreUi.scoreText(recentAverage);
            float scale = UiTheme.scaled(0.44f);
            float valueScale = UiTheme.scaled(0.56f);
            float valueW = r.textWidth(UiFont.BOLD, value, valueScale);
            r.drawText(UiFont.BOLD, value, right - pad - valueW, top - UiTheme.scaled(32f), PvpScoreUi.scoreColor(recentAverage), valueScale, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, recent, right - pad - valueW, top - UiTheme.scaled(32f), (right - left) / 2f - pad,
                UiTheme.TEXT_MUTED, scale, vw, vh);
        }

        // Les notes, de la plus ancienne à la plus récente.
        double[] scores = new double[TREND];
        int count = 0;
        for (DuelHistory.Duel duel : history.duels) {
            if (count == TREND) break;
            if (duel.score() >= 0) scores[count++] = duel.score();
        }
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
        r.drawRoundedRect(chartLeft, reference, chartRight, reference + Math.max(1f, UiTheme.scaled(1f)), 0f,
            new UiColor(255, 255, 255, 40), vw, vh);
        for (int i = 0; i < count; i++) {
            double score = scores[count - 1 - i];
            // Les barres se rangent à droite : la plus récente toujours au bord.
            float x = chartRight - (count - i) * slot + (slot - barW) / 2f;
            float h = Math.max(UiTheme.scaled(3f), height * (float) (score / 100.0));
            UiColor tone = PvpScoreUi.scoreColor(score);
            r.drawGradientRect(x, chartBottom, x + barW, chartBottom + h, Math.min(barW / 2f, UiTheme.scaled(3f)),
                tone.multiplyAlpha(0.55f), tone, vw, vh);
        }
    }

    /** Le titre de la liste, et le nom de chaque colonne au-dessus des lignes. */
    private void drawListTitle(UiRenderer r, float left, float right) {
        int vw = screenWidth, vh = screenHeight;
        float y = heroBottom - UiTheme.scaled(40f);
        r.drawText(UiFont.BOLD, Lang.tr("HISTORIQUE DES DUELS"), left, y, UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.48f), vw, vh);
        if (history.duels.isEmpty()) return;
        float[] col = DuelRow.columns(left, right - left - UiTheme.scaled(18f));
        float small = UiTheme.scaled(0.34f);
        float hy = heroBottom - UiTheme.scaled(68f);
        UiColor muted = UiTheme.TEXT_MUTED;
        r.drawText(Lang.tr("NOTE"), col[0], hy, muted, small, vw, vh);
        r.drawText(Lang.tr("RÉSULTAT"), col[1], hy, muted, small, vw, vh);
        r.drawText(Lang.tr("ADVERSAIRE"), col[2], hy, muted, small, vw, vh);
        r.drawText(Lang.tr("NIVEAU ADVERSE"), col[3], hy, muted, small, vw, vh);
        PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("OPPORTUNITÉS"), col[4], hy, UiTheme.scaled(140f), muted, small, vw, vh);
        PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("AU CONTACT"), col[5], hy, UiTheme.scaled(120f), muted, small, vw, vh);
        PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("DATE"), col[6], hy, UiTheme.scaled(120f), muted, small, vw, vh);
    }

    /**
     * Un duel de l'historique, tout sur une ligne teintée par son résultat :
     * la note dans une pastille de sa couleur, le résultat, l'adversaire et
     * son niveau, les opportunités converties, le temps au contact, la date.
     *
     * <p>La ligne se coupe elle-même au bord de la liste : voir {@link #draw}.
     */
    private static final class DuelRow extends UiWidget {

        private final DuelHistory.Duel duel;
        private final Runnable open;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        /** Les bords de la liste, en pixels d'écran : rien de la ligne n'est dessiné au-delà. */
        private final float clipBottom, clipTop;

        DuelRow(float x, float y, float w, float h, DuelHistory.Duel duel, Runnable open, float clipBottom, float clipTop) {
            super(x, y, w, h);
            this.duel = duel;
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

        /** Plus de fondu aux bords : la ligne est coupée net (voir {@link #draw}). */
        private UiColor faded(UiColor color) {
            return color;
        }

        /**
         * Coupée net au bord de la liste : sur les versions récentes du jeu, la
         * liste ne découpe pas ce qui dépasse de son cadre. Le fondu des bords
         * ({@link UiWidget#clipFade}) laissait la ligne mordre sur les noms des
         * colonnes, et sa barre de niveau — qui ne s'estompait pas — restait
         * visible quand la carte avait disparu.
         *
         * <p>Donc : le fond est dessiné seulement sur sa partie à l'intérieur
         * de la liste, coins carrés du côté coupé ; le contenu (pastille,
         * textes, barre) tient dans une bande au milieu de la ligne et
         * n'apparaît que si cette bande est entière dans la liste. Une ligne
         * à moitié sortie montre donc son fond coupé, sans rien qui déborde.
         */
        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            float visibleBottom = Math.max(y, clipBottom);
            float visibleTop = Math.min(y + h, clipTop);
            if (visibleTop - visibleBottom < 1f) return;
            boolean cutBottom = visibleBottom > y;
            boolean cutTop = visibleTop < y + h;

            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor tone = PvpScoreUi.resultColor(duel.result());
            float radius = UiTheme.scaled(10f);
            float bottomRadius = cutBottom ? 0f : radius;
            float topRadius = cutTop ? 0f : radius;
            // Le fond prend la couleur du résultat en transparence, davantage au survol.
            // Ordre des rayons : bas-gauche, bas-droit, haut-gauche, haut-droit.
            float strength = 0.16f + 0.12f * hover.get();
            r.drawRoundedRect(x, visibleBottom, x + w, visibleTop, bottomRadius, bottomRadius, topRadius, topRadius,
                UiTheme.CARD_BG, vw, vh);
            // Le dégradé n'a qu'un rayon pour ses quatre coins : coupé, il est
            // carré, et rentré d'un rayon sur les côtés pour ne pas dépasser
            // des coins arrondis qui restent.
            float tintInset = cutBottom || cutTop ? radius : 0f;
            r.drawGradientRect2D(x + tintInset, visibleBottom, x + w - tintInset, visibleTop, cutBottom || cutTop ? 0f : radius,
                tone.withAlpha(strength), tone.withAlpha(strength * 0.2f),
                tone.withAlpha(strength), tone.withAlpha(strength * 0.2f), vw, vh);
            float accent = UiTheme.scaled(5f);
            r.drawRoundedRect(x, visibleBottom, x + accent, visibleTop, cutBottom ? 0f : accent / 2f, 0f,
                cutTop ? 0f : accent / 2f, 0f, tone, vw, vh);

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
            r.drawRoundedRect(col[0], chipY, col[0] + chipW, chipY + chipH, UiTheme.scaled(8f),
                faded(scoreTone.withAlpha(0.2f)), vw, vh);
            float strong = UiTheme.scaled(0.86f);
            PvpScoreUi.centered(r, UiFont.BOLD, PvpScoreUi.scoreText(duel.score()), col[0] + chipW / 2f, PvpScoreUi.middle(mid, strong),
                chipW, faded(scoreTone), strong, vw, vh);

            // Le résultat, en capitales et dans sa couleur.
            float result = UiTheme.scaled(0.62f);
            PvpScoreUi.left(r, UiFont.BOLD, PvpScoreUi.resultText(duel.result()).toUpperCase(java.util.Locale.ROOT),
                col[1], PvpScoreUi.middle(mid, result), col[2] - col[1] - UiTheme.scaled(12f), faded(tone), result, vw, vh);

            // L'adversaire.
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            PvpScoreUi.left(r, UiFont.BOLD, opponent, col[2], PvpScoreUi.middle(mid, normal), col[3] - col[2] - UiTheme.scaled(16f),
                faded(UiTheme.TEXT_PRIMARY), normal, vw, vh);

            // Son niveau : la valeur et une petite barre, dans sa couleur à lui.
            double level = duel.report[NativeCombat.REPORT_FAMILIES + NativeCombat.FAMILY_COUNT - 1];
            String levelText = String.valueOf(Math.round(level));
            r.drawText(UiFont.BOLD, levelText, col[3], PvpScoreUi.middle(mid, normal), faded(PvpScoreUi.FOE), normal, vw, vh);
            float barLeft = col[3] + UiTheme.scaled(48f);
            float barRight = Math.min(barLeft + UiTheme.scaled(110f), col[4] - UiTheme.scaled(120f));
            if (barRight > barLeft + UiTheme.scaled(20f)) {
                PvpScoreUi.bar(r, barLeft, mid - UiTheme.scaled(3f), barRight, UiTheme.scaled(6f), level,
                    faded(PvpScoreUi.FOE), vw, vh);
            }

            // Opportunités converties, temps au contact, date.
            String converted = (int) duel.report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) duel.report[NativeCombat.REPORT_MY_OPENED];
            PvpScoreUi.right(r, UiFont.BOLD, converted, col[4], PvpScoreUi.middle(mid, normal), UiTheme.scaled(140f),
                faded(UiTheme.TEXT_PRIMARY), normal, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.duration(duel.report[NativeCombat.REPORT_ENGAGED_SECS]), col[5],
                PvpScoreUi.middle(mid, normal), UiTheme.scaled(130f), faded(UiTheme.TEXT_SECONDARY), normal, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.date(duel.time), col[6], PvpScoreUi.middle(mid, small * 1.2f),
                UiTheme.scaled(140f), faded(UiTheme.TEXT_SECONDARY), small * 1.2f, vw, vh);
        }

        @Override
        public void onClick() {
            open.run();
        }
    }
}
