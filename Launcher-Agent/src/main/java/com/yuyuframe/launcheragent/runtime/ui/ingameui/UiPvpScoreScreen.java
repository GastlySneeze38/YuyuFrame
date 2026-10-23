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
 *       (victoires, défaites, taux de victoire, meilleure note) et la place
 *       du joueur sur la répartition des notes — une cloche de 0 à 500
 *       centrée sur 250, ses deux paliers (330, 420) et sa moyenne du mode ;</li>
 *   <li>l'historique des <b>duels notés</b> du mode choisi dans la liste
 *       déroulante, une ligne par duel teintée par son résultat. Un clic sur
 *       une ligne ouvre la page du duel ({@link UiPvpDuelScreen}).</li>
 * </ol>
 *
 * <p>Tout entre en mouvement : les blocs du bilan montent en fondu l'un après
 * l'autre, les chiffres défilent jusqu'à leur valeur, la courbe se trace,
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

    private final Object lastScreen;
    private final DuelHistory history = DuelHistory.load();
    /** Le mode affiché ({@link PvpScoreUi#MODES}), et ses duels notés, du plus récent au plus ancien. */
    private int mode;
    private final List<DuelHistory.Duel> scored = new ArrayList<DuelHistory.Duel>();
    /** Les lignes de la liste : en compétitif, sans les duels joués en casual (ils n'ont pas compté). */
    private final List<DuelHistory.Duel> listed = new ArrayList<DuelHistory.Duel>();
    private int wins, losses, draws;
    private double best;
    /** La note moyenne des duels notés du mode, -1 sans duel : le repère posé sur la courbe. */
    private double modeAverage = -1;

    private UiScrollContainer scroll;
    private PvpScoreUi.ModeDropdown modeDropdown;
    private PvpScoreUi.PlayTypeToggle playType;
    /** Compétitif ou casual — ce qu'on joue —, sur la carte du rang ; nul hors de la page du compétitif. */
    private PvpScoreUi.PlayingSwitch playing;
    /** La souris à cette image : la carte du rang dessine l'interrupteur elle-même. */
    private double mouseX, mouseY;
    /** La page du compétitif est affichée à la place du bilan et de l'historique. */
    private boolean competitive;
    private PvpInfoModal info;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    /** Bas du bilan, haut de la liste. */
    private float heroBottom, listTop;

    /** Départ des animations de la page (rejoué en passant du casual au compétitif), et de celles de la liste (rejouées au changement de mode). */
    private long openedAt = System.nanoTime();
    private long listShownAt = openedAt;

    public UiPvpScoreScreen(Object lastScreen) {
        super("Score PvP");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        // On arrive sur le compétitif du mode où l'on est le plus avancé :
        // c'est la progression qui donne envie de relancer.
        competitive = true;
        selectMode(mostAdvancedMode());
    }

    /**
     * L'index ({@link PvpScoreUi#MODES}) du mode où le joueur est le plus
     * avancé : un rang dévoilé avant un placement, puis la position la plus
     * haute, puis le plus de duels classés. Le premier mode sans aucun duel.
     */
    private int mostAdvancedMode() {
        int best = 0;
        double bestKey = -1;
        for (int i = 0; i < PvpScoreUi.MODE_CODES.length; i++) {
            DuelHistory.Rank rank = history.rank(PvpScoreUi.MODE_CODES[i]);
            if (rank == null || rank.duels == 0) continue;
            double key = (rank.placed ? 1e9 : 0) + PvpRank.cumulative(rank.division, rank.points) * 1e3 + rank.duels;
            if (key > bestKey) {
                bestKey = key;
                best = i;
            }
        }
        return best;
    }

    /** Champ du classement d'un duel : 1 duel de placement, 2 joué pendant le placement sans y compter. */
    private static final int RANKED_PLACEMENT = 7;

    /** Joué en casual : noté, hors du classement. */
    static boolean isCasual(DuelHistory.Duel duel) {
        return duel.report[NativeCombat.REPORT_CASUAL] >= 1;
    }

    /** La place d'un duel dans le placement (0 s'il est joué après, en casual, ou sans classement). */
    static int placementOf(DuelHistory.Duel duel) {
        if (isCasual(duel)) return 0;
        return duel.ranked != null && duel.ranked.length > RANKED_PLACEMENT ? (int) duel.ranked[RANKED_PLACEMENT] : 0;
    }

    /** Les verdicts (1, 2, 3) des duels de placement d'un mode, du premier au dernier. */
    private int[] placementResults(int code) {
        java.util.List<Integer> results = new java.util.ArrayList<>();
        // L'historique va du plus récent au plus ancien.
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.mode() == code && placementOf(duel) == 1) results.add(0, duel.result());
        }
        int[] out = new int[results.size()];
        for (int i = 0; i < out.length; i++) out[i] = results.get(i);
        return out;
    }

    /**
     * Les duels notés d'un mode, et ce qu'on en compte. Le mode de chaque
     * duel vient du noyau, qui le reconnaît à l'arme en main.
     */
    private void selectMode(int index) {
        mode = index;
        scored.clear();
        int w = 0, l = 0, d = 0;
        double top = -1, sum = 0;
        int code = PvpScoreUi.MODE_CODES[Math.max(0, Math.min(PvpScoreUi.MODE_CODES.length - 1, index))];
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.score() < 0 || duel.mode() != code) continue;
            scored.add(duel);
            if (duel.result() == 1) w++;
            else if (duel.result() == 2) l++;
            else if (duel.result() == 3) d++;
            top = Math.max(top, duel.score());
            sum += duel.score();
        }
        wins = w; losses = l; draws = d;
        best = top;
        // Arrondie ici, pour que la grande note et le repère de la courbe
        // disent exactement le même chiffre.
        modeAverage = scored.isEmpty() ? -1 : Math.round(sum / scored.size());
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
        this.mouseX = mouseX;
        this.mouseY = mouseY;
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
            // Le compétitif remplace le bilan ; la liste reste (tous les duels
            // notés sont classés), avec les points de chaque duel.
            if (competitive) {
                drawCompetitive(renderer, c);
            } else {
                drawHero(renderer, c[0], c[1], c[2] - UiTheme.scaled(24f));
            }
            drawListTitle(renderer, c[0], c[1]);
            if (listed.isEmpty()) {
                PvpScoreUi.alpha = PvpScoreUi.ease(listShownAt, 0.3f, 0.5f);
                String empty = Lang.tr(competitive && !scored.isEmpty()
                    ? "Aucun duel classé pour l'instant : ceux joués en casual sont dans l'onglet Casual."
                    : "Aucun duel noté pour l'instant : ils apparaîtront ici dès le premier.");
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
        com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule module =
            com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule.instance();
        if (module != null) {
            widgets.add(PvpScoreUi.settingsButton(screenWidth, screenHeight, () -> closeTo(new UiModConfigScreen(this, module))));
        }

        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        heroBottom = c[2] - UiTheme.scaled(24f) - heroHeight();
        listTop = heroBottom - UiTheme.scaled(80f);

        float dropW = UiTheme.scaled(230f);
        float dropH = UiTheme.scaled(38f);
        // À gauche, juste au-dessus de la liste qu'elle filtre : c'est là que l'œil la cherche.
        float rowY = heroBottom - UiTheme.scaled(18f) - dropH;
        modeDropdown = new PvpScoreUi.ModeDropdown(c[0], rowY, dropW, dropH, mode, index -> {
            selectMode(index);
            buildList();
        });
        // Casual ou compétitif, À L'INTÉRIEUR du mode choisi : les deux
        // boutons dépendent de la liste d'à côté, qui reste là sur les deux
        // pages.
        playType = new PvpScoreUi.PlayTypeToggle(c[0] + dropW + UiTheme.scaled(22f), rowY, UiTheme.scaled(300f), dropH,
            competitive ? 1 : 0, index -> {
                competitive = index == 1;
                openedAt = System.nanoTime();
                listShownAt = openedAt;
                buildLayout();
            });
        widgets.add(modeDropdown);
        widgets.add(playType);
        playing = null;
        if (competitive) {
            // Sur la carte du rang (moitié gauche du haut de page, voir drawCompetitive) :
            // en compétitif une pastille en haut à droite, en casual un grand
            // bouton centré en bas.
            float split = c[0] + (c[1] - c[0]) * 0.5f;
            float pad = UiTheme.scaled(26f);
            float cardTop = c[2] - UiTheme.scaled(24f);
            if (PvpScoreUi.PlayingSwitch.playing()) {
                float w = UiTheme.scaled(260f), h = UiTheme.scaled(34f);
                playing = new PvpScoreUi.PlayingSwitch(split - pad - w, cardTop - UiTheme.scaled(16f) - h, w, h, this::buildLayout);
                playing.tooltip = Lang.tr("Passer en casual : tes duels seront notés sans toucher à ton rang.");
            } else {
                float w = UiTheme.scaled(320f), h = UiTheme.scaled(50f);
                playing = new PvpScoreUi.PlayingSwitch(c[0] + (split - c[0] - w) / 2f, heroBottom + UiTheme.scaled(26f), w, h, this::buildLayout);
            }
            widgets.add(playing);
        }
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
        listed.clear();
        for (DuelHistory.Duel duel : scored) {
            if (!competitive || !isCasual(duel)) listed.add(duel);
        }
        for (int i = 0; i < listed.size(); i++) {
            DuelHistory.Duel duel = listed.get(i);
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

        // La moyenne du mode choisi, la même que le repère de la courbe : elle
        // défile jusqu'à sa valeur, et recommence au changement de mode.
        float dy = enter(0.05f);
        float avgRight = left + UiTheme.scaled(300f);
        UiColor tone = PvpScoreUi.scoreColor(modeAverage);
        PvpScoreUi.tintedCard(r, left, bottom + dy, avgRight, top + dy, tone, 0.24f, vw, vh);
        float center = (left + avgRight) / 2f;
        float width = avgRight - left - UiTheme.scaled(24f);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("NOTE MOYENNE"), center, top + dy - UiTheme.scaled(34f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);
        String average = modeAverage >= 0
            ? String.valueOf(PvpScoreUi.count(modeAverage, PvpScoreUi.ease(listShownAt, 0.15f, 1.1f)))
            : PvpScoreUi.NO_SCORE;
        PvpScoreUi.centered(r, UiFont.BOLD, average, center, bottom + dy + UiTheme.scaled(64f), width,
            tone, UiTheme.scaled(3.3f), vw, vh);
        int count = scored.size();
        String basis = count + " " + Lang.tr(count > 1 ? "duels notés" : "duel noté");
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

        // Sa place dans la répartition des notes : ce qui reste de la largeur.
        float trendLeft = tilesLeft + 2 * tileW + 2 * gap;
        if (right - trendLeft > UiTheme.scaled(220f)) {
            dy = enter(0.32f);
            drawDistribution(r, trendLeft, bottom + dy, right, top + dy);
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

    /** Les paliers de la courbe : au-delà de 330, puis de 420 (environ 21 % et 4 % des joueurs). */
    private static final double TIER_HIGH = 330.0, TIER_TOP = 420.0;
    /** Couleurs du trait de la courbe : avant le premier palier, entre les deux, au-delà. */
    private static final UiColor CURVE_LOW = new UiColor(72, 168, 190, 255);
    private static final UiColor CURVE_HIGH = new UiColor(236, 110, 214, 255);
    private static final UiColor CURVE_TOP = new UiColor(244, 224, 112, 255);

    /**
     * Où se place le joueur : la répartition des notes de tous les joueurs,
     * une cloche centrée sur 250 (le joueur moyen) — la note est construite
     * ainsi, 100 points par écart habituel — et sa note moyenne du mode
     * choisi posée dessus. Deux paliers teintent le fond. La courbe se trace
     * de gauche à droite, puis le repère du joueur apparaît.
     */
    private void drawDistribution(UiRenderer r, float left, float bottom, float right, float top) {
        int vw = screenWidth, vh = screenHeight;
        float alpha = PvpScoreUi.alpha;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = UiTheme.scaled(14f);
        float chartLeft = left + pad;
        float chartRight = right - pad;
        float chartBottom = bottom + UiTheme.scaled(30f);
        float chartTop = top - pad;
        float width = chartRight - chartLeft;
        // La cloche laisse de la place au-dessus d'elle pour la note du joueur.
        float peak = (chartTop - chartBottom) * 0.78f;

        // Les trois zones du fond.
        float xHigh = xOf(TIER_HIGH, chartLeft, width);
        float xTop = xOf(TIER_TOP, chartLeft, width);
        r.drawGradientRect2D(chartLeft, chartBottom, xHigh, chartTop, 0f,
            new UiColor(16, 30, 38, 0).multiplyAlpha(alpha), new UiColor(26, 58, 82, 120).multiplyAlpha(alpha),
            new UiColor(16, 30, 38, 0).multiplyAlpha(alpha), new UiColor(26, 58, 82, 120).multiplyAlpha(alpha), vw, vh);
        r.drawGradientRect(xHigh, chartBottom, xTop, chartTop, 0f,
            new UiColor(120, 50, 140, 70).multiplyAlpha(alpha), new UiColor(70, 40, 110, 60).multiplyAlpha(alpha), vw, vh);
        r.drawGradientRect(xTop, chartBottom, chartRight, chartTop, 0f,
            new UiColor(170, 170, 160, 70).multiplyAlpha(alpha), new UiColor(110, 115, 115, 50).multiplyAlpha(alpha), vw, vh);

        // La courbe : une colonne de remplissage et un bout de trait tous les
        // deux pixels, envoyés en deux lots. Elle se trace de gauche à droite.
        float drawn = PvpScoreUi.ease(openedAt, 0.4f, 1.1f);
        float step = Math.max(1f, UiTheme.scaled(2f));
        int columns = Math.max(1, (int) Math.ceil(width / step));
        int shown = Math.max(0, Math.min(columns, Math.round(columns * drawn)));
        float thickness = Math.max(1.5f, UiTheme.scaled(2.2f));
        float[][] fill = new float[shown][];
        UiColor[] fillColors = new UiColor[shown];
        float[][] line = new float[shown][];
        UiColor[] lineColors = new UiColor[shown];
        for (int i = 0; i < shown; i++) {
            float x1 = chartLeft + i * step;
            float x2 = Math.min(chartRight, x1 + step);
            double s1 = (x1 - chartLeft) / width * NativeCombat.SCORE_MAX;
            double s2 = (x2 - chartLeft) / width * NativeCombat.SCORE_MAX;
            float y1 = chartBottom + peak * bell(s1);
            float y2 = chartBottom + peak * bell(s2);
            UiColor tone = curveColor((s1 + s2) / 2.0);
            fill[i] = new float[]{ x1, chartBottom, x2 + 0.5f, Math.min(y1, y2) };
            fillColors[i] = tone.multiplyAlpha(0.13f * alpha);
            line[i] = new float[]{ x1, Math.min(y1, y2) - thickness / 2f, x2 + 0.5f, Math.max(y1, y2) + thickness / 2f };
            lineColors[i] = tone.multiplyAlpha(alpha);
        }
        if (shown > 0) {
            r.drawRoundedRectBatch(fill, fillColors, 0f, vw, vh);
            r.drawRoundedRectBatch(line, lineColors, 0f, vw, vh);
        }

        // 250, le joueur moyen : un trait pointillé jusqu'au sommet.
        float x250 = xOf(250.0, chartLeft, width);
        float dash = UiTheme.scaled(6f);
        float dashW = Math.max(1f, UiTheme.scaled(1.4f));
        UiColor dashColor = new UiColor(255, 255, 255, 70).multiplyAlpha(alpha);
        for (float y = chartBottom; y < chartBottom + peak; y += dash * 1.8f) {
            PvpScoreUi.rect(r, x250 - dashW / 2f, y, x250 + dashW / 2f, Math.min(chartBottom + peak, y + dash), 0f, dashColor, vw, vh);
        }

        // L'axe.
        PvpScoreUi.rect(r, chartLeft, chartBottom, chartRight, chartBottom + Math.max(1f, UiTheme.scaled(1f)), 0f,
            new UiColor(255, 255, 255, 30).multiplyAlpha(alpha), vw, vh);
        float labelY = bottom + UiTheme.scaled(10f);
        float labelScale = UiTheme.scaled(0.36f);
        float labelW = UiTheme.scaled(60f);
        PvpScoreUi.left(r, UiFont.REGULAR, "0", chartLeft, labelY, labelW, UiTheme.TEXT_MUTED, labelScale, vw, vh);
        for (double mark : new double[]{ 250.0, TIER_HIGH, TIER_TOP }) {
            PvpScoreUi.centered(r, UiFont.REGULAR, String.valueOf((int) mark), xOf(mark, chartLeft, width), labelY, labelW,
                UiTheme.TEXT_MUTED, labelScale, vw, vh);
        }
        PvpScoreUi.right(r, UiFont.REGULAR, String.valueOf((int) NativeCombat.SCORE_MAX), chartRight, labelY, labelW,
            UiTheme.TEXT_MUTED, labelScale, vw, vh);

        // Le joueur : sa note moyenne du mode, quand il en a une.
        if (modeAverage < 0) {
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Pas encore de duel noté"), (chartLeft + chartRight) / 2f,
                chartBottom + peak + UiTheme.scaled(4f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.4f), vw, vh);
            return;
        }
        float pop = PvpScoreUi.pop(openedAt, 1.2f, 0.5f);
        if (pop <= 0f) return;
        PvpScoreUi.alpha = alpha * Math.min(1f, pop);
        double score = Math.max(0.0, Math.min(NativeCombat.SCORE_MAX, modeAverage));
        float x = xOf(score, chartLeft, width);
        float y = chartBottom + peak * bell(score);
        float stem = Math.max(1f, UiTheme.scaled(1.6f));
        r.drawGradientRect(x - stem / 2f, chartBottom, x + stem / 2f, y, 0f,
            new UiColor(255, 255, 255, 0), new UiColor(255, 255, 255, 230).multiplyAlpha(PvpScoreUi.alpha), vw, vh);
        float dot = UiTheme.scaled(5.5f) * Math.max(0f, pop);
        r.drawRoundedRect(x - dot, y - dot, x + dot, y + dot, dot, new UiColor(255, 255, 255, 255).multiplyAlpha(PvpScoreUi.alpha), vw, vh);
        PvpScoreUi.centered(r, UiFont.BOLD, String.valueOf(Math.round(score)), x, y + UiTheme.scaled(12f), labelW * 2f,
            UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.42f), vw, vh);
        PvpScoreUi.alpha = alpha;
    }

    /** L'abscisse d'une note sur l'axe de 0 à 500. */
    private static float xOf(double score, float left, float width) {
        return left + (float) (score / NativeCombat.SCORE_MAX) * width;
    }

    /** La hauteur de la cloche à une note, 1 à 250 : 100 points valent un écart habituel. */
    private static float bell(double score) {
        double z = (score - 250.0) / 100.0;
        return (float) Math.exp(-0.5 * z * z);
    }

    /** La couleur du trait à une note, fondue sur 20 points autour de chaque palier. */
    private static UiColor curveColor(double score) {
        double blend = 20.0;
        if (score < TIER_HIGH - blend) return CURVE_LOW;
        if (score < TIER_HIGH + blend) return UiColor.lerp(CURVE_LOW, CURVE_HIGH, (float) ((score - TIER_HIGH + blend) / (2 * blend)));
        if (score < TIER_TOP - blend) return CURVE_HIGH;
        if (score < TIER_TOP + blend) return UiColor.lerp(CURVE_HIGH, CURVE_TOP, (float) ((score - TIER_TOP + blend) / (2 * blend)));
        return CURVE_TOP;
    }

    // ── Le compétitif ────────────────────────────────────────────────────────

    /**
     * Le compétitif du mode choisi, à la place de son bilan. Deux cartes :
     *
     * <ul>
     *   <li>le rang, qui attire l'œil : son insigne qui luit doucement, son
     *       nom en grand dans la couleur du matériau, la barre de la division
     *       qui se remplit à l'ouverture, et l'objectif suivant (« encore ~6
     *       victoires avant Bois 2 ») — ou, pendant le placement, les dix
     *       cases et ce qui reste ;</li>
     *   <li>sa place parmi les joueurs : la courbe de la répartition des
     *       rangs (estimée), de Bois à Champion, et son repère dessus.</li>
     * </ul>
     *
     * <p>Tout vient du noyau (`combat/ranked.rs`), rejoué avec l'historique ;
     * noms et couleurs des rangs : {@link PvpRank}.
     */
    private void drawCompetitive(UiRenderer r, float[] c) {
        int vw = screenWidth, vh = screenHeight;
        float left = c[0], right = c[1];
        float top = c[2] - UiTheme.scaled(24f);
        float gap = UiTheme.scaled(16f);
        String modeName = Lang.tr(PvpScoreUi.MODES[Math.max(0, Math.min(PvpScoreUi.MODES.length - 1, mode))]);
        int code = PvpScoreUi.MODE_CODES[Math.max(0, Math.min(PvpScoreUi.MODE_CODES.length - 1, mode))];
        DuelHistory.Rank rank = history.rank(code);
        int duels = rank == null ? 0 : rank.duels;
        boolean placed = rank != null && rank.placed;
        int division = rank == null ? 0 : rank.division;
        double points = rank == null ? 0 : rank.points;

        // ── Le rang ──
        float split = left + (right - left) * 0.5f;
        float dy = enter(0.05f);
        float cardBottom = heroBottom + dy, cardTop = top + dy;
        UiColor tone = placed ? PvpRank.color(division) : UiTheme.ACCENT;
        float breath = 0.5f + 0.5f * (float) Math.sin((System.nanoTime() - openedAt) / 1e9 * 2.0);
        if (placed) {
            // Une auréole qui respire autour de la carte : elle appelle l'œil.
            r.drawGlow(left, cardBottom, split, cardTop, UiTheme.scaled(14f), 0.18f + 0.1f * breath,
                tone.multiplyAlpha(0.6f * PvpScoreUi.alpha), vw, vh);
        }
        PvpScoreUi.tintedCard(r, left, cardBottom, split, cardTop, tone, 0.3f, vw, vh);
        float pad = UiTheme.scaled(26f);
        float badge = Math.min(UiTheme.scaled(120f), cardTop - cardBottom - pad * 2f);
        float badgeX = left + pad;
        float badgeY = cardBottom + (cardTop - cardBottom - badge) / 2f;
        float pop = PvpScoreUi.pop(openedAt, 0.15f, 0.6f);
        if (placed) {
            float size = badge * Math.max(0.01f, pop);
            PvpRank.badge(r, division, badgeX + (badge - size) / 2f, badgeY + (badge - size) / 2f, size,
                0.25f + 0.2f * breath, PvpScoreUi.alpha, vw, vh);
        } else {
            // Pendant le placement, l'insigne est un point d'interrogation.
            PvpScoreUi.rect(r, badgeX, badgeY, badgeX + badge, badgeY + badge, badge * 0.24f,
                UiTheme.ACCENT.withAlpha(0.25f), vw, vh);
            PvpScoreUi.centered(r, UiFont.BOLD, "?", badgeX + badge / 2f, badgeY + badge * 0.28f, badge, UiTheme.ACCENT,
                badge / 44f, vw, vh);
        }

        float textLeft = badgeX + badge + UiTheme.scaled(24f);
        float textRight = split - pad;
        float textW = textRight - textLeft;
        boolean playingRanked = PvpScoreUi.PlayingSwitch.playing();
        // La pastille « TU JOUES EN COMPÉTITIF » occupe le haut à droite : le titre s'arrête avant.
        float titleW = playing != null && playingRanked ? Math.max(UiTheme.scaled(60f), playing.x - UiTheme.scaled(14f) - textLeft) : textW;
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("COMPÉTITIF") + "  ·  " + modeName.toUpperCase(java.util.Locale.ROOT),
            textLeft, cardTop - UiTheme.scaled(40f), titleW, tone, UiTheme.scaled(0.42f), vw, vh);
        if (placed) {
            PvpScoreUi.left(r, UiFont.BOLD, PvpRank.name(division), textLeft, cardTop - UiTheme.scaled(96f), textW,
                tone, UiTheme.scaled(1.55f), vw, vh);
            boolean champion = division >= PvpRank.CHAMPION;
            float barY = cardTop - UiTheme.scaled(126f);
            float fill = champion ? 1f : (float) Math.max(0, Math.min(1, points / PvpRank.DIVISION_POINTS));
            // La barre se lit de 0 à 100.
            PvpScoreUi.bar(r, textLeft, barY, textRight, UiTheme.scaled(12f), 100.0 * fill * PvpScoreUi.ease(openedAt, 0.3f, 1.1f), tone, vw, vh);
            String count = champion ? Math.round(points) + " " + Lang.tr("points")
                : Math.round(points) + " / " + PvpRank.DIVISION_POINTS + " " + Lang.tr("points");
            PvpScoreUi.left(r, UiFont.BOLD, count, textLeft, barY - UiTheme.scaled(30f), textW, UiTheme.TEXT_PRIMARY,
                UiTheme.scaled(0.5f), vw, vh);
            String goal = PvpRank.nextGoal(division, points);
            if (!goal.isEmpty()) {
                PvpScoreUi.right(r, UiFont.REGULAR, goal, textRight, barY - UiTheme.scaled(30f), textW * 0.6f,
                    UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.44f), vw, vh);
            }
        } else {
            PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("Non classé"), textLeft, cardTop - UiTheme.scaled(96f), textW,
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(1.4f), vw, vh);
            // Une case par duel de placement, verte ou rouge selon son verdict.
            int[] results = placementResults(code);
            float pipGap = UiTheme.scaled(6f);
            float pipW = (textW - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
            float pipY = cardTop - UiTheme.scaled(128f);
            for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                float x = textLeft + i * (pipW + pipGap);
                float light = i < duels ? PvpScoreUi.ease(openedAt, 0.3f + 0.06f * i, 0.4f) : 0f;
                UiColor lit = i < results.length ? PvpScoreUi.resultColor(results[i]) : UiTheme.ACCENT;
                PvpScoreUi.rect(r, x, pipY, x + pipW, pipY + UiTheme.scaled(12f), UiTheme.scaled(4f),
                    UiColor.lerp(new UiColor(0, 0, 0, 90), lit, light), vw, vh);
            }
            int remaining = Math.max(0, PvpRank.PLACEMENT_DUELS - duels);
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Placement") + " " + duels + " / " + PvpRank.PLACEMENT_DUELS + "  ·  "
                    + Lang.tr("ton rang se dévoile dans") + " " + remaining + " " + Lang.tr(remaining > 1 ? "duels notés" : "duel noté"),
                textLeft, pipY - UiTheme.scaled(30f), textW, UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.46f), vw, vh);
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Au plus 2 duels contre un même adversaire comptent"),
                textLeft, pipY - UiTheme.scaled(56f), textW, UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);
        }

        if (playing != null) {
            if (!playingRanked) {
                // En casual, la carte se ternit — le rang n'est pas en jeu — et
                // ne dit plus qu'une chose, au centre, au-dessus du bouton.
                PvpScoreUi.rect(r, left, cardBottom, split, cardTop, UiTheme.scaled(14f), new UiColor(8, 8, 12, 205), vw, vh);
                float center = (left + split) / 2f;
                PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("TU JOUES EN CASUAL"), center, playing.y + playing.h + UiTheme.scaled(62f),
                    split - left - pad * 2f, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.62f), vw, vh);
                PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Tes duels sont notés, mais ne comptent pas pour ton rang."),
                    center, playing.y + playing.h + UiTheme.scaled(30f), split - left - pad * 2f, UiTheme.TEXT_SECONDARY,
                    UiTheme.scaled(0.42f), vw, vh);
            }
            playing.paint(r, mouseX, mouseY, tone, vw, vh);
        }

        // ── Sa place parmi les joueurs ──
        dy = enter(0.15f);
        drawRankDistribution(r, split + gap, heroBottom + dy, right, top + dy, placed, division, points);
        PvpScoreUi.alpha = 1f;
    }

    /**
     * Où se place le joueur parmi les autres : la même courbe que la
     * répartition des notes, mais sur l'échelle des rangs — de Bois à
     * Champion, une zone par rang dans la couleur de son matériau, la
     * courbe teintée de même —, son repère dessus et la part des joueurs
     * qu'il dépasse. La répartition est une ESTIMATION ({@link PvpRank#ESTIMATED_SHARE}),
     * dite comme telle : personne ne connaît les rangs des autres.
     */
    private void drawRankDistribution(UiRenderer r, float left, float bottom, float right, float top,
                                      boolean placed, int division, double points) {
        int vw = screenWidth, vh = screenHeight;
        float alpha = PvpScoreUi.alpha;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = UiTheme.scaled(14f);
        float chartLeft = left + pad;
        float chartRight = right - pad;
        float chartBottom = bottom + UiTheme.scaled(30f);
        float chartTop = top - UiTheme.scaled(42f);
        float width = chartRight - chartLeft;
        float peak = (chartTop - chartBottom) * 0.8f;
        double axis = PvpRank.AXIS_DIVISIONS;

        // L'en-tête : le titre, et ce que dit la position.
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("TA PLACE PARMI LES JOUEURS"), left + UiTheme.scaled(20f), top - UiTheme.scaled(30f),
            width * 0.6f, UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.38f), vw, vh);
        double position = PvpRank.axisPosition(division, points);
        long better = placed ? Math.round(PvpRank.percentBelow(position)) : 0;
        // « Devant 0 % » ne dit rien d'utile : on ne l'écrit qu'à partir de 1 %.
        if (better >= 1) {
            PvpScoreUi.right(r, UiFont.BOLD, Lang.tr("Devant") + " " + better + " % " + Lang.tr("des joueurs"),
                right - UiTheme.scaled(20f), top - UiTheme.scaled(30f), width * 0.45f, PvpRank.color(division), UiTheme.scaled(0.42f), vw, vh);
        }

        // Une zone par rang, dans la couleur de son matériau.
        int tiers = PvpRank.TIERS.length;
        float zoneW = width / tiers;
        for (int t = 0; t < tiers; t++) {
            float x1 = chartLeft + t * zoneW;
            UiColor color = PvpRank.color(t * PvpRank.DIVISIONS_PER_TIER);
            boolean current = placed && t == PvpRank.tier(division);
            r.drawGradientRect(x1, chartBottom, x1 + zoneW, chartTop, 0f,
                color.withAlpha((current ? 0.16f : 0.06f) * alpha), color.withAlpha((current ? 0.05f : 0.015f) * alpha), vw, vh);
            PvpScoreUi.centered(r, current ? UiFont.BOLD : UiFont.REGULAR, Lang.tr(PvpRank.TIERS[t]), x1 + zoneW / 2f,
                bottom + UiTheme.scaled(10f), zoneW, current ? color : UiTheme.TEXT_MUTED, UiTheme.scaled(0.34f), vw, vh);
        }

        // La courbe : une colonne de remplissage et un bout de trait tous les
        // deux pixels, en deux lots, tracée de gauche à droite.
        double max = 0;
        for (int i = 0; i <= 200; i++) max = Math.max(max, PvpRank.density(axis * i / 200));
        float drawn = PvpScoreUi.ease(openedAt, 0.4f, 1.1f);
        float step = Math.max(1f, UiTheme.scaled(2f));
        int columns = Math.max(1, (int) Math.ceil(width / step));
        int shown = Math.max(0, Math.min(columns, Math.round(columns * drawn)));
        float thickness = Math.max(1.5f, UiTheme.scaled(2.2f));
        float[][] fill = new float[shown][];
        UiColor[] fillColors = new UiColor[shown];
        float[][] line = new float[shown][];
        UiColor[] lineColors = new UiColor[shown];
        for (int i = 0; i < shown; i++) {
            float x1 = chartLeft + i * step;
            float x2 = Math.min(chartRight, x1 + step);
            double a1 = (x1 - chartLeft) / width * axis;
            double a2 = (x2 - chartLeft) / width * axis;
            float y1 = chartBottom + peak * (float) (PvpRank.density(a1) / max);
            float y2 = chartBottom + peak * (float) (PvpRank.density(a2) / max);
            UiColor tone = PvpRank.color((int) Math.min(axis - 1, (a1 + a2) / 2.0));
            fill[i] = new float[]{ x1, chartBottom, x2 + 0.5f, Math.min(y1, y2) };
            fillColors[i] = tone.multiplyAlpha(0.14f * alpha);
            line[i] = new float[]{ x1, Math.min(y1, y2) - thickness / 2f, x2 + 0.5f, Math.max(y1, y2) + thickness / 2f };
            lineColors[i] = tone.multiplyAlpha(alpha);
        }
        if (shown > 0) {
            r.drawRoundedRectBatch(fill, fillColors, 0f, vw, vh);
            r.drawRoundedRectBatch(line, lineColors, 0f, vw, vh);
        }
        PvpScoreUi.rect(r, chartLeft, chartBottom, chartRight, chartBottom + Math.max(1f, UiTheme.scaled(1f)), 0f,
            new UiColor(255, 255, 255, 30), vw, vh);

        if (!placed) {
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Ta place apparaît après le placement"), (chartLeft + chartRight) / 2f,
                chartBottom + peak + UiTheme.scaled(6f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);
        } else {
            // Le repère : la ligne qui monte de l'axe, le point blanc sur la
            // courbe, et le rang au-dessus. Il apparaît une fois la courbe tracée.
            float pop = PvpScoreUi.pop(openedAt, 1.2f, 0.5f);
            if (pop > 0f) {
                PvpScoreUi.alpha = alpha * Math.min(1f, pop);
                float x = chartLeft + (float) (position / axis) * width;
                float y = chartBottom + peak * (float) (PvpRank.density(position) / max);
                UiColor rank = PvpRank.color(division);
                float stem = Math.max(1f, UiTheme.scaled(1.6f));
                r.drawGradientRect(x - stem / 2f, chartBottom, x + stem / 2f, y, 0f,
                    new UiColor(255, 255, 255, 0), new UiColor(255, 255, 255, 230).multiplyAlpha(PvpScoreUi.alpha), vw, vh);
                float dot = UiTheme.scaled(5.5f) * Math.max(0f, pop);
                r.drawGlow(x - dot, y - dot, x + dot, y + dot, dot, 0.5f, rank.multiplyAlpha(PvpScoreUi.alpha), vw, vh);
                r.drawRoundedRect(x - dot, y - dot, x + dot, y + dot, dot, new UiColor(255, 255, 255, 255).multiplyAlpha(PvpScoreUi.alpha), vw, vh);
                PvpScoreUi.centered(r, UiFont.BOLD, PvpRank.name(division), x, y + UiTheme.scaled(12f), UiTheme.scaled(140f),
                    rank, UiTheme.scaled(0.42f), vw, vh);
                PvpScoreUi.alpha = alpha;
            }
        }
        // L'estimation, dite.
        PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("répartition estimée"), chartRight, chartTop - UiTheme.scaled(4f),
            width * 0.4f, UiTheme.TEXT_MUTED, UiTheme.scaled(0.28f), vw, vh);
    }

    /** Le nom de chaque colonne au-dessus des lignes (le mode et le casual / compétitif sont des widgets). */
    private void drawListTitle(UiRenderer r, float left, float right) {
        int vw = screenWidth, vh = screenHeight;
        PvpScoreUi.alpha = PvpScoreUi.ease(openedAt, 0.3f, 0.5f);
        if (!listed.isEmpty()) {
            float[] col = DuelRow.columns(left, right - left - UiTheme.scaled(18f));
            float small = UiTheme.scaled(0.34f);
            float hy = heroBottom - UiTheme.scaled(74f);
            UiColor muted = UiTheme.TEXT_MUTED;
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr(competitive ? "POINTS" : "NOTE"), col[0], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("RÉSULTAT"), col[1], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("ADVERSAIRE"), col[2], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("NIVEAU ADVERSE"), col[3], hy, muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr(competitive ? "NOTE" : "OPPORTUNITÉS"), col[4], hy, UiTheme.scaled(140f), muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.REGULAR, Lang.tr("DURÉE"), col[5], hy, UiTheme.scaled(120f), muted, small, vw, vh);
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
            boolean cut = cutBottom || cutTop;
            float tintInset = cut ? radius : 0f;
            r.drawGradientRect2D(x + tintInset, visibleBottom, x + w - tintInset, visibleTop, cut ? 0f : radius,
                PvpScoreUi.a(tone.withAlpha(strength)), PvpScoreUi.a(tone.withAlpha(strength * 0.2f)),
                PvpScoreUi.a(tone.withAlpha(strength)), PvpScoreUi.a(tone.withAlpha(strength * 0.2f)), vw, vh);
            if (cut) {
                // Les deux bandes laissées par ce retrait, sinon vides : la
                // carte grise nue s'y voyait comme une bande sombre au bord
                // d'une ligne coupée. Un rect arrondi coin par coin, de la
                // couleur du dégradé à cet endroit — sur la largeur d'un
                // rayon, sa pente ne se voit pas.
                float edge = (radius / 2f) / w;
                UiColor leftTint = PvpScoreUi.a(tone.withAlpha(strength * (1f - 0.8f * edge)));
                UiColor rightTint = PvpScoreUi.a(tone.withAlpha(strength * (0.2f + 0.8f * edge)));
                r.drawRoundedRect(x, visibleBottom, x + radius, visibleTop, bottomRadius, 0f, topRadius, 0f, leftTint, vw, vh);
                r.drawRoundedRect(x + w - radius, visibleBottom, x + w, visibleTop, 0f, bottomRadius, 0f, topRadius, rightTint, vw, vh);
            }
            float accent = UiTheme.scaled(5f) + lift * UiTheme.scaled(2f);
            r.drawRoundedRect(x, visibleBottom, x + accent, visibleTop, cutBottom ? 0f : accent / 2f, 0f,
                cutTop ? 0f : accent / 2f, 0f, PvpScoreUi.a(tone), vw, vh);

            float[] col = columns(x, w);
            float mid = y + h / 2f;
            float normal = UiTheme.scaled(0.6f);
            float small = UiTheme.scaled(0.4f);

            // La pastille : en compétitif, les points du duel — ce qu'on
            // regarde d'abord — ; en casual, la note. Dans sa couleur.
            float chipW = UiTheme.scaled(80f);
            float chipH = UiTheme.scaled(42f);
            float chipY = mid - chipH / 2f;
            // Le contenu entier, ou rien.
            if (chipY < clipBottom || chipY + chipH > clipTop) return;
            float strong = UiTheme.scaled(0.86f);
            float counting = PvpScoreUi.ease(listShownAt, delay() + 0.1f, 0.8f);
            UiColor chipTone;
            String chip;
            int placement = placementOf(duel);
            if (competitive && placement != 0) {
                // Un duel de placement n'a pas de points à montrer : ils restent
                // cachés jusqu'au rang de départ.
                chipTone = placement == 1 ? UiTheme.ACCENT : PvpScoreUi.NEUTRAL;
                chip = Lang.tr(placement == 1 ? "PLACEMENT" : "NON COMPTÉ");
                strong = UiTheme.scaled(0.36f);
            } else if (competitive) {
                boolean ranked = duel.ranked != null && duel.ranked.length > 0;
                double total = ranked ? duel.ranked[0] : 0;
                chipTone = !ranked ? PvpScoreUi.NEUTRAL : total > 0 ? PvpScoreUi.GOOD : total < 0 ? PvpScoreUi.BAD : PvpScoreUi.NEUTRAL;
                chip = ranked ? PvpScoreUi.signedPoints(total * counting) : PvpScoreUi.NO_SCORE;
            } else {
                chipTone = PvpScoreUi.scoreColor(duel.score());
                chip = String.valueOf(PvpScoreUi.count(duel.score(), counting));
            }
            PvpScoreUi.rect(r, col[0], chipY, col[0] + chipW, chipY + chipH, UiTheme.scaled(8f),
                chipTone.withAlpha(0.2f + 0.1f * lift), vw, vh);
            PvpScoreUi.centered(r, UiFont.BOLD, chip, col[0] + chipW / 2f, PvpScoreUi.middle(mid, strong),
                chipW, chipTone, strong, vw, vh);

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
                PvpScoreUi.bar(r, barLeft, mid - UiTheme.scaled(3f), barRight, UiTheme.scaled(6f), PvpScoreUi.percent(level) * grow, PvpScoreUi.FOE, vw, vh);
            }

            // Opportunités converties — en compétitif, la note, passée de la
            // pastille à cette colonne —, durée du duel, date.
            if (competitive) {
                PvpScoreUi.right(r, UiFont.BOLD, PvpScoreUi.scoreText(duel.score()), col[4], PvpScoreUi.middle(mid, normal),
                    UiTheme.scaled(140f), PvpScoreUi.scoreColor(duel.score()), normal, vw, vh);
            } else {
                String converted = (int) duel.report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) duel.report[NativeCombat.REPORT_MY_OPENED];
                PvpScoreUi.right(r, UiFont.BOLD, converted, col[4], PvpScoreUi.middle(mid, normal), UiTheme.scaled(140f),
                    UiTheme.TEXT_PRIMARY, normal, vw, vh);
            }
            PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.duration(duel.report[NativeCombat.REPORT_DURATION_SECS]), col[5],
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
