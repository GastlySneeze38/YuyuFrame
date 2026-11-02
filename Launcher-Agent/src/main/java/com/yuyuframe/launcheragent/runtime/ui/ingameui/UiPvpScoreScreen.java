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
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

/**
 * La page du module « Score PvP », en plein écran.
 *
 * <p>Trois colonnes, comme l'écran Compétitif du launcher (2026-11-01) :
 * <ol>
 *   <li><b>à gauche</b>, une colonne haute : en compétitif la carte du rang
 *       (insigne, nom, barre de la division, ce qu'on joue) ; en casual la
 *       note moyenne et ses quatre chiffres (victoires, défaites, taux,
 *       meilleure note) ;</li>
 *   <li><b>au centre</b>, l'historique : dans l'en-tête de sa carte le mode et
 *       casual / compétitif, puis une ligne par duel noté. Un clic sur une
 *       ligne ouvre la page du duel ({@link UiPvpDuelScreen}) ;</li>
 *   <li><b>à droite</b>, une colonne haute : la place du joueur, une courbe
 *       couchée — l'échelle monte de bas en haut (de Bois à Champion, ou de 0
 *       à 500) et la répartition s'étend vers la droite.</li>
 * </ol>
 *
 * <p>Tout entre en mouvement : les colonnes montent en fondu l'une après
 * l'autre, les chiffres défilent jusqu'à leur valeur, la courbe se trace de
 * bas en haut, les lignes glissent depuis la droite en cascade — et
 * recommencent quand on change de mode.
 *
 * <p>L'historique est demandé au noyau natif une fois, à l'ouverture
 * ({@link DuelHistory#load}) : il relit et renote les journaux, ce n'est pas
 * un travail à refaire à chaque image. Les notes et la moyenne viennent du
 * noyau ; ici, on ne fait que compter ce qui est affiché.
 */
public final class UiPvpScoreScreen extends UiScreenBase {

    private static final int GLASS_PASSES = 3;

    private final Object lastScreen;
    private DuelHistory history = DuelHistory.load();
    /** La réponse du noyau au dernier passage : le serveur de classement est-il joint ? */
    private int rankedReady = NativeCombat.rankedReady();
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
    /** La page du compétitif est affichée à la place du bilan. */
    private boolean competitive;
    private PvpInfoModal info;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;

    /** Les trois colonnes, et la liste dans celle du centre. */
    private float colLeft, colRight, colTop, colBottom, leftRight, centerLeft, centerRight, rightLeft;
    private float listTop, listBottom, listX, listW;

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

    // Le fond est dessiné par la page elle-même (voile + cartes de verre) :
    // le voile plat de la base passerait par-dessus le flou.
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
        // La synchronisation avec le serveur vient d'aboutir (ou de tomber) :
        // le rang affiché et l'interrupteur suivent sans rouvrir l'écran.
        int ready = NativeCombat.rankedReady();
        if (ready != rankedReady && screenWidth > 0) {
            rankedReady = ready;
            if (ready == NativeCombat.RANKED_READY_YES) {
                history = DuelHistory.load();
                selectMode(mode);
            }
            buildLayout();
        }
        if (info != null && info.closed()) info = null;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        try {
            PvpScoreUi.drawPage(renderer, Lang.tr("SCORE PVP"), screenWidth, screenHeight);
            // La carte du centre AVANT les widgets : son en-tête (mode,
            // casual / compétitif) est posé dessus.
            float dy = enter(0.12f);
            PvpScoreUi.card(renderer, centerLeft, colBottom + dy, centerRight, colTop + dy, screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] fond : " + t);
        } finally {
            PvpScoreUi.alpha = 1f;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            if (competitive) {
                drawRankColumn(renderer);
                drawRankPlace(renderer);
            } else {
                drawScoreColumn(renderer);
                drawScorePlace(renderer);
            }
            drawListTitle(renderer);
            if (listed.isEmpty()) {
                PvpScoreUi.alpha = PvpScoreUi.ease(listShownAt, 0.3f, 0.5f);
                String empty = Lang.tr(competitive && !scored.isEmpty()
                    ? "Aucun duel classé pour l'instant : ceux joués en casual sont dans l'onglet Casual."
                    : "Aucun duel noté pour l'instant : ils apparaîtront ici dès le premier.");
                PvpScoreUi.centered(renderer, UiFont.REGULAR, empty, (centerLeft + centerRight) / 2f, (listTop + listBottom) / 2f,
                    centerRight - centerLeft - innerPad() * 2f, UiTheme.TEXT_MUTED, UiTheme.scaled(0.46f), screenWidth, screenHeight);
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

    // ── Mise en page ─────────────────────────────────────────────────────────

    /** L'écart entre la barre du haut et les colonnes. */
    private static float topGap() {
        return UiTheme.scaled(32f);
    }

    /** L'écart entre deux colonnes. */
    private static float blockGap() {
        return UiTheme.scaled(24f);
    }

    /** Le rembourrage à l'intérieur d'une carte. */
    private static float innerPad() {
        return UiTheme.scaled(28f);
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

        // Les trois colonnes : deux côtés de même largeur, la liste au milieu.
        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        float gap = blockGap();
        float side = Math.max(UiTheme.scaled(250f), Math.min(UiTheme.scaled(320f), (c[1] - c[0]) * 0.23f));
        colLeft = c[0];
        colRight = c[1];
        colTop = c[2] - topGap();
        colBottom = c[3];
        leftRight = colLeft + side;
        centerLeft = leftRight + gap;
        rightLeft = colRight - side;
        centerRight = rightLeft - gap;

        // L'en-tête de la carte du centre : le mode, puis casual / compétitif.
        float pad = innerPad();
        float dropH = UiTheme.scaled(40f);
        float rowY = colTop - pad - dropH;
        float dropW = Math.min(UiTheme.scaled(230f), (centerRight - centerLeft - pad * 2f) * 0.42f);
        modeDropdown = new PvpScoreUi.ModeDropdown(centerLeft + pad, rowY, dropW, dropH, mode, index -> {
            selectMode(index);
            buildList();
        });
        float toggleLeft = centerLeft + pad + dropW + UiTheme.scaled(14f);
        float toggleW = Math.min(UiTheme.scaled(300f), centerRight - pad - toggleLeft);
        // Casual ou compétitif, À L'INTÉRIEUR du mode choisi.
        playType = new PvpScoreUi.PlayTypeToggle(toggleLeft, rowY, toggleW, dropH,
            competitive ? 1 : 0, index -> {
                competitive = index == 1;
                openedAt = System.nanoTime();
                listShownAt = openedAt;
                buildLayout();
            });
        widgets.add(modeDropdown);
        widgets.add(playType);

        // Sous l'en-tête : les noms des colonnes, puis la liste.
        float listPad = pad * 0.7f;
        listTop = rowY - UiTheme.scaled(52f);
        listBottom = colBottom + listPad;
        listX = centerLeft + listPad;
        listW = centerRight - centerLeft - listPad * 2f;

        playing = null;
        if (competitive) {
            // Sur la carte du rang, à gauche : en compétitif une pastille tout
            // en bas ; en casual un grand bouton, au tiers bas de la carte.
            float x = colLeft + pad, w = side - pad * 2f;
            if (PvpScoreUi.PlayingSwitch.playing()) {
                float h = UiTheme.scaled(36f);
                playing = new PvpScoreUi.PlayingSwitch(x, upperCardBottom() + pad, w, h, this::buildLayout);
                playing.tooltip = Lang.tr("Passer en casual : tes duels seront notés sans toucher à ton rang.");
            } else {
                // En casual, le bouton ferme la carte casual, tout en bas.
                float h = UiTheme.scaled(50f);
                playing = new PvpScoreUi.PlayingSwitch(x, upperCardBottom() + pad, w, h, this::buildLayout);
            }
            widgets.add(playing);
        }
        buildList();
    }

    /** Les lignes de l'historique, pour le mode choisi. */
    private void buildList() {
        float rowH = UiTheme.scaled(64f);
        float gap = UiTheme.scaled(10f);
        scroll = new UiScrollContainer(listX, listBottom, listW, Math.max(1f, listTop - listBottom));
        float rowW = listW - UiTheme.scaled(18f);
        listed.clear();
        for (DuelHistory.Duel duel : scored) {
            if (!competitive || !isCasual(duel)) listed.add(duel);
        }
        for (int i = 0; i < listed.size(); i++) {
            DuelHistory.Duel duel = listed.get(i);
            scroll.add(new DuelRow(listX, -(i * (rowH + gap)) - rowH, rowW, rowH, duel, i,
                () -> closeTo(new UiPvpDuelScreen(this, duel)), listBottom, listTop));
        }
    }

    /** Un bloc qui entre : fondu, et montée depuis un peu plus bas. Rend le décalage vertical à appliquer. */
    private float enter(float delay) {
        float p = PvpScoreUi.ease(openedAt, delay, 0.5f);
        PvpScoreUi.alpha = p;
        return -(1f - p) * UiTheme.scaled(26f);
    }

    // ── Colonne de gauche, en casual : la note moyenne et ses chiffres ──────

    /** Une case de chiffre en bas de la colonne de gauche, et l'écart entre elles. */
    private static float tileHeight() {
        return UiTheme.scaled(96f);
    }

    private static float tileGap() {
        return UiTheme.scaled(16f);
    }

    /**
     * Le bas de la carte du haut de la colonne de gauche (moyenne, ou rang) :
     * les quatre cases de chiffres prennent la place en dessous, sur les deux
     * vues.
     */
    private float upperCardBottom() {
        return colBottom + tileHeight() * 2f + tileGap() * 2f;
    }

    /**
     * Les quatre chiffres, deux par deux, en bas de la colonne de gauche :
     * victoires, défaites, taux de victoire, meilleure note. Comptés sur les
     * duels de la liste : en compétitif les duels classés seulement, en casual
     * tous les duels notés du mode.
     */
    private void drawStatTiles(UiRenderer r) {
        float left = colLeft, right = leftRight;
        float gap = tileGap(), tileH = tileHeight();
        int w = 0, l = 0, d = 0;
        double top = -1;
        for (DuelHistory.Duel duel : listed) {
            if (duel.result() == 1) w++;
            else if (duel.result() == 2) l++;
            else if (duel.result() == 3) d++;
            top = Math.max(top, duel.score());
        }
        float tileW = (right - left - gap) / 2f;
        int played = w + l + d;
        float rateProgress = PvpScoreUi.ease(openedAt, 0.4f, 1f);
        String rate = played > 0 ? PvpScoreUi.count(100.0 * w / played, rateProgress) + " %" : PvpScoreUi.NO_SCORE;
        float row1 = colBottom + tileH + gap;
        drawTile(r, 0, left, row1, left + tileW, row1 + tileH, w, null, Lang.tr("VICTOIRES"), PvpScoreUi.GOOD);
        drawTile(r, 1, left + tileW + gap, row1, right, row1 + tileH, l, null, Lang.tr("DÉFAITES"), PvpScoreUi.BAD);
        drawTile(r, 2, left, colBottom, left + tileW, colBottom + tileH, -1, rate, Lang.tr("TAUX DE VICTOIRE"),
            played > 0 ? (w * 2 >= played ? PvpScoreUi.GOOD : PvpScoreUi.BAD) : PvpScoreUi.NEUTRAL);
        drawTile(r, 3, left + tileW + gap, colBottom, right, colBottom + tileH, top, null, Lang.tr("MEILLEURE NOTE"),
            PvpScoreUi.scoreColor(top));
        PvpScoreUi.alpha = 1f;
    }

    private void drawScoreColumn(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        float left = colLeft, right = leftRight;
        float avgBottom = upperCardBottom();

        // La moyenne du mode choisi, la même que le repère de la courbe : elle
        // défile jusqu'à sa valeur, et recommence au changement de mode.
        float dy = enter(0.05f);
        UiColor tone = PvpScoreUi.scoreColor(modeAverage);
        PvpScoreUi.tintedCard(r, left, avgBottom + dy, right, colTop + dy, tone, 0.3f, vw, vh);
        float center = (left + right) / 2f;
        float width = right - left - innerPad();
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("NOTE MOYENNE"), center, colTop + dy - UiTheme.scaled(48f), width,
            UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);
        String average = modeAverage >= 0
            ? String.valueOf(PvpScoreUi.count(modeAverage, PvpScoreUi.ease(listShownAt, 0.15f, 1.1f)))
            : PvpScoreUi.NO_SCORE;
        float mid = (avgBottom + colTop) / 2f + dy;
        PvpScoreUi.centered(r, UiFont.BOLD, average, center, mid - UiTheme.scaled(20f), width, tone, UiTheme.scaled(3.2f), vw, vh);
        PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("sur 500"), center, mid - UiTheme.scaled(50f), width,
            UiTheme.TEXT_MUTED, UiTheme.scaled(0.42f), vw, vh);
        int count = scored.size();
        String basis = count + " " + Lang.tr(count > 1 ? "duels notés" : "duel noté");
        PvpScoreUi.centered(r, UiFont.REGULAR, basis, center, avgBottom + dy + UiTheme.scaled(30f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.42f), vw, vh);

        // Les quatre chiffres, deux par deux, sous la moyenne.
        drawStatTiles(r);
    }

    /**
     * Une case de chiffre, qui entre à son tour. {@code value} positif défile
     * jusqu'à sa valeur ; sinon {@code text} est affiché tel quel.
     */
    private void drawTile(UiRenderer r, int index, float x1, float y1, float x2, float y2, double value, String text,
                          String label, UiColor tone) {
        float dy = enter(0.12f + 0.07f * index);
        y1 += dy;
        y2 += dy;
        PvpScoreUi.card(r, x1, y1, x2, y2, screenWidth, screenHeight);
        float center = (x1 + x2) / 2f;
        float width = x2 - x1 - UiTheme.scaled(16f);
        String shown = value >= 0
            ? String.valueOf(PvpScoreUi.count(value, PvpScoreUi.ease(openedAt, 0.2f + 0.07f * index, 0.9f)))
            : text != null ? text : PvpScoreUi.NO_SCORE;
        PvpScoreUi.centered(r, UiFont.BOLD, shown, center, y1 + (y2 - y1) * 0.46f, width, tone, UiTheme.scaled(0.95f), screenWidth, screenHeight);
        PvpScoreUi.centered(r, UiFont.BOLD, label, center, y1 + UiTheme.scaled(18f), width, UiTheme.TEXT_MUTED,
            UiTheme.scaled(0.3f), screenWidth, screenHeight);
    }

    // ── Colonne de gauche, en compétitif : le rang ──────────────────────────

    /**
     * La carte du rang, sur toute la hauteur : en haut le mode, l'insigne qui
     * luit doucement et le nom du rang ; en bas la barre de la division et
     * l'objectif suivant (« encore ~6 victoires avant Bois 2 ») — ou, pendant
     * le placement, les dix cases et ce qui reste — puis ce qu'on joue.
     *
     * <p>Tout vient du noyau (`combat/ranked.rs`), rejoué avec l'historique ;
     * noms et couleurs des rangs : {@link PvpRank}.
     */
    private void drawRankColumn(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        float left = colLeft, right = leftRight;
        String modeName = Lang.tr(PvpScoreUi.MODES[Math.max(0, Math.min(PvpScoreUi.MODES.length - 1, mode))]);
        int code = PvpScoreUi.MODE_CODES[Math.max(0, Math.min(PvpScoreUi.MODE_CODES.length - 1, mode))];
        DuelHistory.Rank rank = history.rank(code);
        int duels = rank == null ? 0 : rank.duels;
        boolean placed = rank != null && rank.placed;
        int division = rank == null ? 0 : rank.division;
        double points = rank == null ? 0 : rank.points;

        float dy = enter(0.05f);
        // La carte du rang s'arrête au-dessus des quatre cases de chiffres.
        float top = colTop + dy, bottom = upperCardBottom() + dy;
        UiColor tone = placed ? PvpRank.color(division) : UiTheme.ACCENT;
        if (playing != null && !PvpScoreUi.PlayingSwitch.playing()) {
            // En casual, la carte du rang laisse sa place à une carte à elle :
            // ce qu'on joue d'abord, le rang ensuite, en petit.
            drawCasualCard(r, left, right, top, bottom, modeName, placed, division, duels, tone);
            playing.paint(r, mouseX, mouseY, tone, vw, vh);
            PvpScoreUi.alpha = 1f;
            drawStatTiles(r);
            return;
        }
        PvpScoreUi.tintedCard(r, left, bottom, right, top, tone, 0.3f, vw, vh);
        float pad = innerPad();
        float center = (left + right) / 2f;
        float width = right - left - pad * 2f;

        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("COMPÉTITIF") + "  ·  " + modeName.toUpperCase(java.util.Locale.ROOT),
            center, top - UiTheme.scaled(48f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);

        // L'insigne, au centre du haut de la carte.
        // L'insigne prend ce que laissent le texte du haut et la progression du
        // bas : il rétrécit sur une fenêtre basse plutôt que de les chevaucher.
        float badge = Math.max(UiTheme.scaled(56f),
            Math.min(Math.min(UiTheme.scaled(150f), width - UiTheme.scaled(20f)), (top - bottom) - UiTheme.scaled(380f)));
        float badgeTop = top - UiTheme.scaled(80f);
        float badgeX = center - badge / 2f, badgeY = badgeTop - badge;
        float breath = 0.5f + 0.5f * (float) Math.sin((System.nanoTime() - openedAt) / 1e9 * 2.0);
        float pop = PvpScoreUi.pop(openedAt, 0.15f, 0.6f);
        float size = badge * Math.max(0.01f, pop);
        if (placed) {
            PvpRank.badge(r, division, badgeX + (badge - size) / 2f, badgeY + (badge - size) / 2f, size,
                0.25f + 0.2f * breath, PvpScoreUi.alpha, vw, vh);
        } else {
            // Pendant le placement : le logo de base, gris, et un « ? » à la place de la division.
            PvpRank.placementBadge(r, badgeX + (badge - size) / 2f, badgeY + (badge - size) / 2f, size, PvpScoreUi.alpha, vw, vh);
        }

        // Le nom du rang, et combien de duels classés.
        float nameY = badgeY - UiTheme.scaled(64f);
        PvpScoreUi.centered(r, UiFont.BOLD, placed ? PvpRank.name(division) : Lang.tr("Non classé"), center, nameY, width,
            placed ? tone : UiTheme.TEXT_PRIMARY, UiTheme.scaled(1.25f), vw, vh);
        PvpScoreUi.centered(r, UiFont.REGULAR, duels + " " + Lang.tr(duels > 1 ? "duels classés" : "duel classé"), center,
            nameY - UiTheme.scaled(32f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.42f), vw, vh);

        // En bas : la progression, au-dessus de ce qu'on joue.
        boolean playingRanked = PvpScoreUi.PlayingSwitch.playing();
        float base = bottom + pad + (playing != null && playingRanked ? playing.h + UiTheme.scaled(28f) : 0f);
        if (placed) {
            boolean champion = division >= PvpRank.CHAMPION;
            String goal = PvpRank.nextGoal(division, points);
            if (!goal.isEmpty()) {
                PvpScoreUi.centered(r, UiFont.REGULAR, goal, center, base, width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.4f), vw, vh);
            }
            float barY = base + UiTheme.scaled(32f);
            float fill = champion ? 1f : (float) Math.max(0, Math.min(1, points / PvpRank.DIVISION_POINTS));
            String count = champion ? Math.round(points) + " " + Lang.tr("points")
                : Math.round(points) + " / " + PvpRank.DIVISION_POINTS + " " + Lang.tr("points");
            PvpScoreUi.centered(r, UiFont.BOLD, count, center, barY + UiTheme.scaled(20f), width, UiTheme.TEXT_PRIMARY,
                UiTheme.scaled(0.44f), vw, vh);
            // La barre se lit de 0 à 100, et se remplit à l'ouverture.
            PvpScoreUi.bar(r, left + pad, barY, right - pad, UiTheme.scaled(8f),
                100.0 * fill * PvpScoreUi.ease(openedAt, 0.3f, 1.1f), tone, vw, vh);
        } else {
            // Une case par duel de placement, verte ou rouge selon son verdict.
            int[] results = placementResults(code);
            int remaining = Math.max(0, PvpRank.PLACEMENT_DUELS - duels);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Au plus 2 duels contre un même adversaire comptent"), center, base,
                width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.34f), vw, vh);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("ton rang se dévoile dans") + " " + remaining + " "
                    + Lang.tr(remaining > 1 ? "duels notés" : "duel noté"), center, base + UiTheme.scaled(24f), width,
                UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.4f), vw, vh);
            float pipY = base + UiTheme.scaled(56f);
            float pipGap = UiTheme.scaled(5f);
            float pipW = (width - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
            for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                float x = left + pad + i * (pipW + pipGap);
                float light = i < duels ? PvpScoreUi.ease(openedAt, 0.3f + 0.06f * i, 0.4f) : 0f;
                UiColor lit = i < results.length ? PvpScoreUi.resultColor(results[i]) : UiTheme.ACCENT;
                PvpScoreUi.rect(r, x, pipY, x + pipW, pipY + UiTheme.scaled(8f), UiTheme.scaled(4f),
                    UiColor.lerp(new UiColor(255, 255, 255, 16), lit, light), vw, vh);
            }
            PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("Placement") + " " + duels + " / " + PvpRank.PLACEMENT_DUELS, center,
                pipY + UiTheme.scaled(24f), width, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.44f), vw, vh);
        }

        if (playing != null) {
            playing.paint(r, mouseX, mouseY, tone, vw, vh);
        }
        PvpScoreUi.alpha = 1f;

        // Les quatre chiffres, sous la carte du rang, au même endroit qu'en casual.
        drawStatTiles(r);
    }

    /**
     * La carte de gauche quand on joue en casual. Elle se lit de haut en bas :
     * ce qu'on joue et ce que ça veut dire, puis le rang compétitif en petit
     * (il ne bouge pas), et tout en bas le bouton pour y revenir. Pas de voile
     * par-dessus la carte du rang : une seule chose à lire à la fois.
     */
    private void drawCasualCard(UiRenderer r, float left, float right, float top, float bottom, String modeName,
                                boolean placed, int division, int duels, UiColor tone) {
        int vw = screenWidth, vh = screenHeight;
        float pad = innerPad();
        float center = (left + right) / 2f;
        float width = right - left - pad * 2f;
        PvpScoreUi.tintedCard(r, left, bottom, right, top, PvpScoreUi.NEUTRAL, 0.3f, vw, vh);

        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("CASUAL") + "  ·  " + modeName.toUpperCase(java.util.Locale.ROOT),
            center, top - UiTheme.scaled(48f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);

        // Ce qu'on joue, en grand, et pourquoi en dessous.
        float titleScale = UiTheme.scaled(0.62f);
        float y = top - UiTheme.scaled(104f);
        for (String line : PvpScoreUi.wrap(r, UiFont.BOLD, Lang.tr("TU JOUES EN CASUAL"), titleScale, width)) {
            PvpScoreUi.centered(r, UiFont.BOLD, line, center, y, width, UiTheme.TEXT_PRIMARY, titleScale, vw, vh);
            y -= UiTheme.scaled(30f);
        }
        boolean available = PvpScoreUi.PlayingSwitch.available();
        String why = available
            ? Lang.tr("Tes duels sont notés, mais ne comptent pas pour ton rang.")
            : PvpScoreUi.PlayingSwitch.unavailableReason();
        float textScale = UiTheme.scaled(0.42f);
        y -= UiTheme.scaled(6f);
        for (String line : PvpScoreUi.wrap(r, UiFont.REGULAR, why, textScale, width)) {
            PvpScoreUi.centered(r, UiFont.REGULAR, line, center, y, width,
                available ? UiTheme.TEXT_SECONDARY : PvpScoreUi.MID, textScale, vw, vh);
            y -= UiTheme.scaled(22f);
        }

        // Le rang compétitif, en petit, au milieu de ce qui reste au-dessus du bouton.
        float buttonTop = playing.y + playing.h;
        float blockH = UiTheme.scaled(104f);
        float room = y - buttonTop;
        if (room < blockH + UiTheme.scaled(16f)) return;
        float blockTop = buttonTop + (room + blockH) / 2f;
        PvpScoreUi.rect(r, left + pad, blockTop + UiTheme.scaled(14f), right - pad, blockTop + UiTheme.scaled(15f), 0f,
            new UiColor(255, 255, 255, 22), vw, vh);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("TON RANG EN COMPÉTITIF"), center, blockTop - UiTheme.scaled(16f), width,
            UiTheme.TEXT_MUTED, UiTheme.scaled(0.34f), vw, vh);
        float badge = UiTheme.scaled(56f);
        String name = placed ? PvpRank.name(division) : Lang.tr("Non classé");
        String detail = placed
            ? duels + " " + Lang.tr(duels > 1 ? "duels classés" : "duel classé")
            : Lang.tr("Placement") + " " + duels + " / " + PvpRank.PLACEMENT_DUELS;
        float nameScale = UiTheme.scaled(0.56f);
        float detailScale = UiTheme.scaled(0.36f);
        float textW = Math.max(r.textWidth(UiFont.BOLD, name, nameScale), r.textWidth(UiFont.REGULAR, detail, detailScale));
        float gap = UiTheme.scaled(12f);
        float rowW = Math.min(width, badge + gap + textW);
        float rowX = center - rowW / 2f;
        float badgeY = blockTop - UiTheme.scaled(36f) - badge;
        if (placed) {
            PvpRank.badge(r, division, rowX, badgeY, badge, 0.2f, PvpScoreUi.alpha, vw, vh);
        } else {
            PvpRank.placementBadge(r, rowX, badgeY, badge, PvpScoreUi.alpha, vw, vh);
        }
        float textX = rowX + badge + gap;
        float textMax = right - pad - textX;
        PvpScoreUi.left(r, UiFont.BOLD, name, textX, badgeY + badge * 0.52f, textMax,
            placed ? tone : UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
        PvpScoreUi.left(r, UiFont.REGULAR, detail, textX, badgeY + badge * 0.16f, textMax,
            UiTheme.TEXT_MUTED, detailScale, vw, vh);
    }

    // ── Colonne de droite : la place du joueur, courbe couchée ──────────────

    /** Les paliers de la courbe des notes : au-delà de 330, puis de 420 (environ 21 % et 4 % des joueurs). */
    private static final double TIER_HIGH = 330.0, TIER_TOP = 420.0;
    /** Couleurs du trait de la courbe des notes : avant le premier palier, entre les deux, au-delà. */
    private static final UiColor CURVE_LOW = new UiColor(110, 168, 186, 255);
    private static final UiColor CURVE_HIGH = new UiColor(200, 130, 196, 255);
    private static final UiColor CURVE_TOP = new UiColor(222, 204, 128, 255);

    /** Les bornes du graphique dans la colonne de droite : {gauche (l'axe), droite, bas, haut}. */
    private static float[] placeChart(float left, float right, float bottom, float top, float labelW) {
        float pad = innerPad();
        return new float[]{ left + pad + labelW, right - pad, bottom + UiTheme.scaled(52f), top - UiTheme.scaled(124f) };
    }

    /**
     * La courbe couchée : l'échelle monte de {@code chart[2]} à {@code chart[3]},
     * la répartition s'étend depuis l'axe ({@code chart[0]}) vers la droite.
     * {@code height} rend la hauteur (0..1) en un point de l'échelle (0..1),
     * {@code color} sa couleur. Une rangée de remplissage et un bout de trait
     * tous les deux pixels, en deux lots ; elle se trace de bas en haut.
     */
    private void drawLyingCurve(UiRenderer r, float[] chart, DoubleUnaryOperator height, DoubleFunction<UiColor> color) {
        int vw = screenWidth, vh = screenHeight;
        float alpha = PvpScoreUi.alpha;
        float axisX = chart[0], reach = (chart[1] - chart[0]) * 0.92f;
        float span = chart[3] - chart[2];
        float drawn = PvpScoreUi.ease(openedAt, 0.4f, 1.1f);
        float step = Math.max(1f, UiTheme.scaled(2f));
        int rows = Math.max(1, (int) Math.ceil(span / step));
        int shown = Math.max(0, Math.min(rows, Math.round(rows * drawn)));
        float thickness = Math.max(1.5f, UiTheme.scaled(2.2f));
        float[][] fill = new float[shown][];
        UiColor[] fillColors = new UiColor[shown];
        float[][] line = new float[shown][];
        UiColor[] lineColors = new UiColor[shown];
        for (int i = 0; i < shown; i++) {
            float y1 = chart[2] + i * step;
            float y2 = Math.min(chart[3], y1 + step);
            double a1 = (y1 - chart[2]) / span, a2 = (y2 - chart[2]) / span;
            float x1 = axisX + reach * (float) height.applyAsDouble(a1);
            float x2 = axisX + reach * (float) height.applyAsDouble(a2);
            UiColor tone = color.apply((a1 + a2) / 2.0);
            fill[i] = new float[]{ axisX, y1, Math.min(x1, x2), y2 + 0.5f };
            fillColors[i] = tone.multiplyAlpha(0.08f * alpha);
            line[i] = new float[]{ Math.min(x1, x2) - thickness / 2f, y1, Math.max(x1, x2) + thickness / 2f, y2 + 0.5f };
            lineColors[i] = tone.multiplyAlpha(0.85f * alpha);
        }
        if (shown > 0) {
            r.drawRoundedRectBatch(fill, fillColors, 0f, vw, vh);
            r.drawRoundedRectBatch(line, lineColors, 0f, vw, vh);
        }
        // L'axe, vertical.
        PvpScoreUi.rect(r, axisX - Math.max(1f, UiTheme.scaled(1f)), chart[2], axisX, chart[3], 0f,
            new UiColor(255, 255, 255, 30), vw, vh);
    }

    /**
     * Le repère du joueur sur la courbe couchée, à la hauteur {@code at}
     * (0..1) : un trait depuis l'axe, le point blanc, et son nom au-dessus.
     * Il apparaît une fois la courbe tracée.
     */
    private void drawLyingMarker(UiRenderer r, float[] chart, double at, double heightAt, String label, UiColor glow) {
        int vw = screenWidth, vh = screenHeight;
        float pop = PvpScoreUi.pop(openedAt, 1.2f, 0.5f);
        if (pop <= 0f) return;
        float alpha = PvpScoreUi.alpha;
        PvpScoreUi.alpha = alpha * Math.min(1f, pop);
        float y = chart[2] + (float) at * (chart[3] - chart[2]);
        float x = chart[0] + (chart[1] - chart[0]) * 0.92f * (float) heightAt;
        float stem = Math.max(1f, UiTheme.scaled(1.6f));
        PvpScoreUi.rect(r, chart[0], y - stem / 2f, x, y + stem / 2f, 0f, new UiColor(255, 255, 255, 170), vw, vh);
        float dot = UiTheme.scaled(5.5f) * Math.max(0f, pop);
        if (glow != null) r.drawGlow(x - dot, y - dot, x + dot, y + dot, dot, 0.5f, glow.multiplyAlpha(PvpScoreUi.alpha), vw, vh);
        r.drawRoundedRect(x - dot, y - dot, x + dot, y + dot, dot, new UiColor(255, 255, 255, 255).multiplyAlpha(PvpScoreUi.alpha), vw, vh);
        // Le nom au-dessus du point, gardé dans la carte.
        float labelW = UiTheme.scaled(140f);
        float lx = Math.min(chart[1] - labelW / 2f, Math.max(chart[0] + labelW / 2f, x));
        PvpScoreUi.centered(r, UiFont.BOLD, label, lx, y + UiTheme.scaled(12f), labelW, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.42f), vw, vh);
        PvpScoreUi.alpha = alpha;
    }

    /**
     * En compétitif : où se place le joueur parmi les autres, sur l'échelle
     * des rangs — de Bois en bas à Champion en haut, une bande par rang (seule
     * la sienne se teinte), la courbe couchée dans la couleur des matériaux,
     * son repère dessus et la part des joueurs qu'il dépasse. La répartition
     * est une ESTIMATION ({@link PvpRank#ESTIMATED_SHARE}), dite comme telle.
     */
    private void drawRankPlace(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        int code = PvpScoreUi.MODE_CODES[Math.max(0, Math.min(PvpScoreUi.MODE_CODES.length - 1, mode))];
        DuelHistory.Rank rank = history.rank(code);
        boolean placed = rank != null && rank.placed;
        int division = rank == null ? 0 : rank.division;
        double points = rank == null ? 0 : rank.points;

        float dy = enter(0.2f);
        float left = rightLeft, right = colRight;
        float top = colTop + dy, bottom = colBottom + dy;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = innerPad();
        float width = right - left - pad * 2f;
        PvpScoreUi.label(r, Lang.tr("TA PLACE PARMI LES JOUEURS"), left + pad, top - UiTheme.scaled(48f), width, vw, vh);
        double position = PvpRank.axisPosition(division, points);
        long better = placed ? Math.round(PvpRank.percentBelow(position)) : 0;
        // « Devant 0 % » ne dit rien d'utile : on ne l'écrit qu'à partir de 1 %.
        if (better >= 1) {
            PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("Devant") + " " + better + " % " + Lang.tr("des joueurs"), left + pad,
                top - UiTheme.scaled(82f), width, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.52f), vw, vh);
        } else if (!placed) {
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Ta place apparaît après le placement"), left + pad,
                top - UiTheme.scaled(82f), width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.4f), vw, vh);
        }

        float labelW = UiTheme.scaled(72f);
        float[] chart = placeChart(left, right, bottom, top, labelW);
        double axis = PvpRank.AXIS_DIVISIONS;
        int tiers = PvpRank.TIERS.length;
        float bandH = (chart[3] - chart[2]) / tiers;
        for (int t = 0; t < tiers; t++) {
            float y1 = chart[2] + t * bandH;
            UiColor color = PvpRank.color(t * PvpRank.DIVISIONS_PER_TIER);
            boolean current = placed && t == PvpRank.tier(division);
            if (current) {
                PvpScoreUi.rect(r, chart[0], y1, chart[1], y1 + bandH, UiTheme.scaled(4f), color.withAlpha(0.08f), vw, vh);
            }
            PvpScoreUi.right(r, current ? UiFont.BOLD : UiFont.REGULAR, Lang.tr(PvpRank.TIERS[t]), chart[0] - UiTheme.scaled(12f),
                PvpScoreUi.middle(y1 + bandH / 2f, UiTheme.scaled(0.36f)), labelW - UiTheme.scaled(8f),
                current ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, UiTheme.scaled(0.36f), vw, vh);
        }

        double max = 0;
        for (int i = 0; i <= 200; i++) max = Math.max(max, PvpRank.density(axis * i / 200));
        final double peak = max;
        drawLyingCurve(r, chart, a -> PvpRank.density(a * axis) / peak,
            a -> PvpRank.color((int) Math.min(axis - 1, a * axis)));

        if (placed) {
            drawLyingMarker(r, chart, position / axis, PvpRank.density(position) / peak, PvpRank.name(division), PvpRank.color(division));
        }
        // L'estimation, dite.
        PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("répartition estimée"), left + pad, bottom + UiTheme.scaled(22f),
            width, UiTheme.TEXT_MUTED, UiTheme.scaled(0.3f), vw, vh);
        PvpScoreUi.alpha = 1f;
    }

    /**
     * En casual : la répartition des notes de tous les joueurs, couchée — de
     * 0 en bas à 500 en haut —, une cloche centrée sur 250 (le joueur moyen ;
     * 100 points valent un écart habituel), ses deux paliers (330, 420) à
     * peine voilés, et la note moyenne du mode choisi posée dessus.
     */
    private void drawScorePlace(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        float dy = enter(0.2f);
        float left = rightLeft, right = colRight;
        float top = colTop + dy, bottom = colBottom + dy;
        PvpScoreUi.card(r, left, bottom, right, top, vw, vh);
        float pad = innerPad();
        float width = right - left - pad * 2f;
        PvpScoreUi.label(r, Lang.tr("TA PLACE PARMI LES JOUEURS"), left + pad, top - UiTheme.scaled(48f), width, vw, vh);
        if (modeAverage < 0) {
            PvpScoreUi.left(r, UiFont.REGULAR, Lang.tr("Pas encore de duel noté"), left + pad, top - UiTheme.scaled(82f), width,
                UiTheme.TEXT_MUTED, UiTheme.scaled(0.4f), vw, vh);
        } else {
            long better = Math.round(100.0 * normalCdf((modeAverage - 250.0) / 100.0));
            if (better >= 1) {
                PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("Devant") + " " + better + " % " + Lang.tr("des joueurs"), left + pad,
                    top - UiTheme.scaled(82f), width, UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.52f), vw, vh);
            }
        }

        float labelW = UiTheme.scaled(48f);
        float[] chart = placeChart(left, right, bottom, top, labelW);
        float span = chart[3] - chart[2];
        double max = NativeCombat.SCORE_MAX;
        // Les deux paliers, à peine voilés.
        float yHigh = chart[2] + (float) (TIER_HIGH / max) * span, yTop = chart[2] + (float) (TIER_TOP / max) * span;
        PvpScoreUi.rect(r, chart[0], yHigh, chart[1], yTop, 0f, CURVE_HIGH.withAlpha(0.06f), vw, vh);
        PvpScoreUi.rect(r, chart[0], yTop, chart[1], chart[3], 0f, CURVE_TOP.withAlpha(0.06f), vw, vh);
        for (double mark : new double[]{ 0.0, 250.0, TIER_HIGH, TIER_TOP, max }) {
            float y = chart[2] + (float) (mark / max) * span;
            PvpScoreUi.right(r, UiFont.REGULAR, String.valueOf((int) mark), chart[0] - UiTheme.scaled(12f),
                PvpScoreUi.middle(y, UiTheme.scaled(0.34f)), labelW - UiTheme.scaled(8f), UiTheme.TEXT_MUTED, UiTheme.scaled(0.34f), vw, vh);
        }
        drawLyingCurve(r, chart, a -> bell(a * max), a -> curveColor(a * max));
        if (modeAverage >= 0) {
            double score = Math.max(0.0, Math.min(max, modeAverage));
            drawLyingMarker(r, chart, score / max, bell(score), String.valueOf(Math.round(score)), null);
        }
        PvpScoreUi.alpha = 1f;
    }

    /** La hauteur de la cloche à une note, 1 à 250 : 100 points valent un écart habituel. */
    private static double bell(double score) {
        double z = (score - 250.0) / 100.0;
        return Math.exp(-0.5 * z * z);
    }

    /** La part d'une loi normale sous {@code z} (approximation d'Abramowitz et Stegun, largement assez précise ici). */
    private static double normalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989423 * Math.exp(-z * z / 2.0);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        return z > 0 ? 1.0 - p : p;
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

    // ── La liste ─────────────────────────────────────────────────────────────

    /** Le nom de chaque colonne au-dessus des lignes (le mode et le casual / compétitif sont des widgets). */
    private void drawListTitle(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        PvpScoreUi.alpha = PvpScoreUi.ease(openedAt, 0.3f, 0.5f);
        if (!listed.isEmpty()) {
            float[] col = DuelRow.columns(listX, listW - UiTheme.scaled(18f));
            float small = UiTheme.scaled(0.34f);
            float hy = listTop + UiTheme.scaled(16f);
            UiColor muted = UiTheme.TEXT_MUTED;
            PvpScoreUi.text(r, UiFont.BOLD, Lang.tr(competitive ? "POINTS" : "NOTE"), col[0], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("ADVERSAIRE"), col[1], hy, muted, small, vw, vh);
            PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("NIVEAU ADVERSE"), col[2], hy, muted, small, vw, vh);
            PvpScoreUi.right(r, UiFont.BOLD, Lang.tr(competitive ? "NOTE" : "OPPORTUNITÉS"), col[3], hy, UiTheme.scaled(140f), muted, small, vw, vh);
        }
        PvpScoreUi.alpha = 1f;
    }

    /**
     * Un duel de l'historique, une carte de verre sur deux lignes : à gauche
     * la pastille (points en compétitif, note en casual), puis l'adversaire et
     * dessous le résultat, la date et la durée ; à droite le niveau adverse et
     * sa barre, puis la note (ou les opportunités converties). Le résultat se
     * lit sur un trait fin à gauche et sur la couleur de son mot.
     *
     * <p>Elle entre en glissant depuis la droite, à son tour dans la cascade ;
     * au survol, elle se décale un peu et son liseré s'éclaire. Elle se coupe
     * elle-même au bord de la liste : voir {@link #drawRow}.
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
         * Les repères d'une ligne de largeur {@code w} : débuts de la pastille,
         * de l'adversaire et du niveau adverse, puis le BORD DROIT de la note.
         */
        static float[] columns(float x, float w) {
            float pad = UiTheme.scaled(22f);
            float right = x + w - pad;
            float noteW = UiTheme.scaled(110f);
            float levelW = UiTheme.scaled(150f);
            return new float[]{
                x + pad,
                x + pad + UiTheme.scaled(76f) + UiTheme.scaled(18f),
                right - noteW - UiTheme.scaled(24f) - levelW,
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
         * côté coupé ; le contenu n'apparaît que si sa bande centrale est
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
            float x = this.x + (1f - p) * UiTheme.scaled(70f) + lift * UiTheme.scaled(4f);

            UiColor tone = PvpScoreUi.resultColor(duel.result());
            float radius = PvpScoreUi.radius() * 0.8f;
            float bottomRadius = cutBottom ? 0f : radius;
            float topRadius = cutTop ? 0f : radius;
            // Ordre des rayons : bas-gauche, bas-droit, haut-gauche, haut-droit.
            UiColor fallback = PvpScoreUi.a(UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, lift));
            UiColor border = r.isGlassAvailable()
                ? PvpScoreUi.a(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, lift)) : null;
            r.drawGlassPanel(x, visibleBottom, x + w, visibleTop, bottomRadius, bottomRadius, topRadius, topRadius,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_CARD - 0.08f * lift, fallback,
                border, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            float stripe = Math.max(2f, UiTheme.scaled(3f));
            float inset = UiTheme.scaled(14f);
            float stripeBottom = Math.max(visibleBottom, y + inset), stripeTop = Math.min(visibleTop, y + h - inset);
            if (stripeTop > stripeBottom) {
                r.drawRoundedRect(x + UiTheme.scaled(8f), stripeBottom, x + UiTheme.scaled(8f) + stripe, stripeTop, stripe / 2f,
                    PvpScoreUi.a(tone.withAlpha(0.75f + 0.25f * lift)), vw, vh);
            }

            float[] col = columns(x, w);
            float mid = y + h / 2f;
            float normal = UiTheme.scaled(0.52f);
            float small = UiTheme.scaled(0.38f);

            // La pastille : en compétitif, les points du duel — ce qu'on
            // regarde d'abord — ; en casual, la note. Dans sa couleur.
            float chipW = UiTheme.scaled(76f);
            float chipH = UiTheme.scaled(34f);
            float chipY = mid - chipH / 2f;
            // Le contenu entier, ou rien.
            if (chipY < clipBottom || chipY + chipH > clipTop) return;
            float strong = UiTheme.scaled(0.72f);
            float counting = PvpScoreUi.ease(listShownAt, delay() + 0.1f, 0.8f);
            UiColor chipTone;
            String chip;
            int placement = placementOf(duel);
            if (competitive && placement != 0) {
                // Un duel de placement n'a pas de points à montrer : ils restent
                // cachés jusqu'au rang de départ.
                chipTone = placement == 1 ? UiTheme.ACCENT : PvpScoreUi.NEUTRAL;
                chip = Lang.tr(placement == 1 ? "PLACEMENT" : "NON COMPTÉ");
                strong = UiTheme.scaled(0.32f);
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
                chipTone.withAlpha(0.1f + 0.06f * lift), vw, vh);
            PvpScoreUi.centered(r, UiFont.BOLD, chip, col[0] + chipW / 2f, PvpScoreUi.middle(mid, strong),
                chipW - UiTheme.scaled(8f), chipTone, strong, vw, vh);

            // L'adversaire, et dessous : le résultat dans sa couleur, la date, la durée.
            float textW = col[2] - col[1] - UiTheme.scaled(20f);
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            PvpScoreUi.left(r, UiFont.BOLD, opponent, col[1], mid + UiTheme.scaled(3f), textW, UiTheme.TEXT_PRIMARY, normal, vw, vh);
            String result = PvpScoreUi.resultText(duel.result()).toUpperCase(java.util.Locale.ROOT);
            float subY = mid - UiTheme.scaled(16f);
            PvpScoreUi.text(r, UiFont.BOLD, result, col[1], subY, tone, small, vw, vh);
            float resultW = r.textWidth(UiFont.BOLD, result, small);
            String rest = "  ·  " + PvpScoreUi.date(duel.time) + "  ·  " + PvpScoreUi.duration(duel.report[NativeCombat.REPORT_DURATION_SECS]);
            PvpScoreUi.left(r, UiFont.REGULAR, rest, col[1] + resultW, subY, Math.max(UiTheme.scaled(20f), textW - resultW),
                UiTheme.TEXT_MUTED, small, vw, vh);

            // Son niveau : la valeur et une petite barre qui pousse, dans sa couleur à lui.
            double level = duel.report[NativeCombat.REPORT_FAMILIES + NativeCombat.FAMILY_COUNT - 1];
            PvpScoreUi.text(r, UiFont.BOLD, String.valueOf(Math.round(level)), col[2], PvpScoreUi.middle(mid, normal), PvpScoreUi.FOE, normal, vw, vh);
            float barLeft = col[2] + UiTheme.scaled(46f);
            float barRight = col[2] + UiTheme.scaled(150f);
            float grow = PvpScoreUi.ease(listShownAt, delay() + 0.15f, 0.7f);
            PvpScoreUi.bar(r, barLeft, mid - UiTheme.scaled(2.5f), barRight, UiTheme.scaled(5f), PvpScoreUi.percent(level) * grow, PvpScoreUi.FOE, vw, vh);

            // En compétitif la note (passée de la pastille à cette colonne) ;
            // en casual les opportunités converties.
            if (competitive) {
                PvpScoreUi.right(r, UiFont.BOLD, PvpScoreUi.scoreText(duel.score()), col[3], PvpScoreUi.middle(mid, normal),
                    UiTheme.scaled(110f), PvpScoreUi.scoreColor(duel.score()), normal, vw, vh);
            } else {
                String converted = (int) duel.report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) duel.report[NativeCombat.REPORT_MY_OPENED];
                PvpScoreUi.right(r, UiFont.BOLD, converted, col[3], PvpScoreUi.middle(mid, normal), UiTheme.scaled(110f),
                    UiTheme.TEXT_PRIMARY, normal, vw, vh);
            }
        }

        @Override
        public void onClick() {
            open.run();
        }
    }
}
