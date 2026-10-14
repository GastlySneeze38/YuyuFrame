package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;

import java.util.List;

/**
 * La page d'un duel, en plein écran, ouverte depuis l'historique de
 * {@link UiPvpScoreScreen}. De haut en bas :
 * <ol>
 *   <li>le bandeau, teinté par le résultat : l'adversaire, le résultat, la date ;</li>
 *   <li>la note en grand et sa confiance ; à côté, le <b>face-à-face</b> :
 *       chaque famille avec ma valeur à gauche et celle de l'adversaire à
 *       droite, mon jeu seul et son niveau en tête. Un clic sur une famille
 *       ouvre son explication, avec ce qu'elle valait dans ce duel ;</li>
 *   <li>les chiffres du duel : opportunités, dégâts de smash, temps au
 *       contact, charge au smash, ping ;</li>
 *   <li>l'analyse — points forts, points faibles, à améliorer. <b>La place
 *       est réservée, le contenu n'existe pas encore</b> : il viendra du
 *       noyau natif, comme le reste ({@link #ANALYSIS}).</li>
 * </ol>
 *
 * <p>La page se construit sous les yeux : le bandeau descend, les blocs
 * montent en fondu l'un après l'autre, la note et les valeurs défilent
 * jusqu'à leur chiffre, les barres du face-à-face poussent depuis le milieu.
 *
 * <p>Tout vient du résumé calculé par le noyau — rien n'est calculé ici.
 */
public final class UiPvpDuelScreen extends UiScreenBase {

    private static final int GLASS_PASSES = 3;

    /** Les trois cases de l'analyse. */
    private static final String[] ANALYSIS = { "Points forts", "Points faibles", "À améliorer" };
    private static final UiColor[] ANALYSIS_TONES = { PvpScoreUi.GOOD, PvpScoreUi.BAD, new UiColor(168, 152, 255, 255) };

    /** Les lignes du face-à-face : mes cinq familles ; l'adversaire n'a que les quatre premières. */
    private static final int ROWS = NativeCombat.FAMILY_COUNT - 1;

    private final Object lastScreen;
    private final DuelHistory.Duel duel;
    private PvpInfoModal info;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    private final long openedAt = System.nanoTime();

    // La mise en page, calculée avec les widgets : les lignes cliquables du
    // face-à-face doivent tomber exactement sur ce qui est dessiné.
    private float left, right, bannerTop, bannerBottom, mainBottom, scoreRight, figuresBottom, contentBottom;
    private final UiWidget[] familyHits = new UiWidget[ROWS];
    private final UiAnimatedFloat[] familyHover = new UiAnimatedFloat[ROWS];
    private UiWidget opponentHit;
    private final UiAnimatedFloat opponentHover = new UiAnimatedFloat(0f, 16f);

    public UiPvpDuelScreen(Object lastScreen, DuelHistory.Duel duel) {
        super("Duel");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.duel = duel;
        for (int i = 0; i < ROWS; i++) familyHover[i] = new UiAnimatedFloat(0f, 16f);
    }

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

    private void openInfo(int topic) {
        info = new PvpInfoModal(duel.report, topic);
    }

    // ── Mise en page ─────────────────────────────────────────────────────────

    private static float bannerHeight() { return UiTheme.scaled(108f); }
    private static float gap() { return UiTheme.scaled(18f); }
    private static float rowHeight() { return UiTheme.scaled(46f); }
    private static float faceHeader() { return UiTheme.scaled(62f); }
    private static float figuresHeight() { return UiTheme.scaled(96f); }

    private void buildLayout() {
        widgets.clear();
        widgets.add(PvpScoreUi.backButton(screenWidth, screenHeight, () -> closeTo(lastScreen)));
        widgets.add(PvpScoreUi.helpButton(screenWidth, screenHeight, () -> openInfo(PvpInfoModal.OVERVIEW)));

        float[] c = PvpScoreUi.content(screenWidth, screenHeight);
        left = c[0];
        right = c[1];
        contentBottom = c[3];
        bannerTop = c[2] - UiTheme.scaled(24f);
        bannerBottom = bannerTop - bannerHeight();
        float mainTop = bannerBottom - gap();
        mainBottom = mainTop - faceHeader() - ROWS * rowHeight() - UiTheme.scaled(16f);
        scoreRight = left + UiTheme.scaled(330f);
        figuresBottom = mainBottom - gap() - figuresHeight();

        // Une zone cliquable par ligne du face-à-face, et une sur l'en-tête de l'adversaire.
        float faceLeft = scoreRight + gap();
        float rowTop = mainTop - faceHeader();
        for (int i = 0; i < ROWS; i++) {
            final int family = i;
            familyHits[i] = new Hit(faceLeft, rowTop - (i + 1) * rowHeight(), right - faceLeft, rowHeight(),
                () -> openInfo(PvpInfoModal.FAMILY_TOPICS[family]));
            widgets.add(familyHits[i]);
        }
        float half = (right - faceLeft) / 2f;
        opponentHit = new Hit(faceLeft + half, mainTop - faceHeader(), half, faceHeader(),
            () -> openInfo(PvpInfoModal.FAMILY_TOPICS[NativeCombat.FAMILY_COUNT - 1]));
        widgets.add(opponentHit);
    }

    /** Une zone cliquable sans dessin : la page dessine elle-même son survol, par-dessus ses cartes. */
    private static final class Hit extends UiWidget {
        private final Runnable action;

        Hit(float x, float y, float w, float h, Runnable action) {
            super(x, y, w, h);
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        }

        @Override
        public void onClick() {
            action.run();
        }
    }

    // ── Animations ───────────────────────────────────────────────────────────

    /** Un bloc qui entre : fondu, et montée depuis un peu plus bas. Rend le décalage vertical à appliquer. */
    private float enter(float delay) {
        float p = PvpScoreUi.ease(openedAt, delay, 0.5f);
        PvpScoreUi.alpha = p;
        return -(1f - p) * UiTheme.scaled(26f);
    }

    private float progress(float delay, float duration) {
        return PvpScoreUi.ease(openedAt, delay, duration);
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

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
            PvpScoreUi.drawPage(renderer, Lang.tr("DUEL"), screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpDuelScreen] fond : " + t);
        }
        super.uiDraw(mouseX, mouseY);
        try {
            boolean hover = info == null;
            drawBanner(renderer);
            drawScore(renderer, bannerBottom - gap());
            drawFaceToFace(renderer, bannerBottom - gap(), hover ? mouseX : -1, hover ? mouseY : -1);
            drawFigures(renderer, mainBottom - gap());
            drawAnalysis(renderer, figuresBottom - gap(), contentBottom);
            PvpScoreUi.alpha = 1f;
            if (info != null) info.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpDuelScreen] uiDraw: " + t);
        } finally {
            PvpScoreUi.alpha = 1f;
        }
    }

    /** Le bandeau, teinté par le résultat, qui descend en entrant : l'adversaire à gauche, le résultat et la date à droite. */
    private void drawBanner(UiRenderer r) {
        int vw = screenWidth, vh = screenHeight;
        float dy = -enter(0f);
        float top = bannerTop + dy, bottom = bannerBottom + dy;
        UiColor tone = PvpScoreUi.resultColor(duel.result());
        PvpScoreUi.tintedCard(r, left, bottom, right, top, tone, 0.3f, vw, vh);
        // La barre d'accent se dessine de bas en haut.
        float accent = progress(0.15f, 0.5f);
        PvpScoreUi.rect(r, left, bottom, left + UiTheme.scaled(6f), bottom + (top - bottom) * accent, UiTheme.scaled(3f), tone, vw, vh);

        float inner = UiTheme.scaled(30f);
        float half = (right - left) / 2f;
        PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("CONTRE"), left + inner, top - UiTheme.scaled(34f), UiTheme.TEXT_SECONDARY,
            UiTheme.scaled(0.42f), vw, vh);
        String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
        float nameScale = PvpScoreUi.fit(r, UiFont.BOLD, opponent, UiTheme.scaled(1.05f), half * 0.55f);
        PvpScoreUi.left(r, UiFont.BOLD, opponent, left + inner, bottom + UiTheme.scaled(24f), half - inner,
            UiTheme.TEXT_PRIMARY, nameScale, vw, vh);

        // Ce qu'on savait de lui avant ce duel : c'est cela qui compte dans la note.
        int met = (int) duel.report[NativeCombat.REPORT_FOE_KNOWN_DUELS];
        float known = UiTheme.scaled(0.42f);
        float knownX = left + inner + r.textWidth(UiFont.BOLD, opponent, nameScale) + UiTheme.scaled(22f);
        float knownY = bottom + UiTheme.scaled(30f);
        if (met > 0) {
            String before = Lang.tr("Force connue avant ce duel :") + " ";
            String value = String.valueOf(Math.round(duel.report[NativeCombat.REPORT_FOE_KNOWN]));
            String basis = " (" + met + " " + Lang.tr(met > 1 ? "duels passés" : "duel passé") + ")";
            PvpScoreUi.text(r, UiFont.REGULAR, before, knownX, knownY, UiTheme.TEXT_SECONDARY, known, vw, vh);
            float x = knownX + r.textWidth(UiFont.REGULAR, before, known);
            PvpScoreUi.text(r, UiFont.BOLD, value, x, knownY, PvpScoreUi.FOE, known, vw, vh);
            PvpScoreUi.text(r, UiFont.REGULAR, basis, x + r.textWidth(UiFont.BOLD, value, known), knownY, UiTheme.TEXT_MUTED, known, vw, vh);
        } else {
            PvpScoreUi.text(r, UiFont.REGULAR, Lang.tr("Premier duel contre lui"), knownX, knownY, UiTheme.TEXT_MUTED, known, vw, vh);
        }

        // Le résultat arrive de la droite, un peu après le reste.
        float arrive = progress(0.12f, 0.55f);
        PvpScoreUi.alpha = arrive;
        String result = PvpScoreUi.resultText(duel.result()).toUpperCase(java.util.Locale.ROOT);
        float slide = (1f - arrive) * UiTheme.scaled(40f);
        PvpScoreUi.right(r, UiFont.BOLD, result, right - inner + slide, top - UiTheme.scaled(62f), half - inner,
            tone, UiTheme.scaled(1.3f), vw, vh);
        PvpScoreUi.right(r, UiFont.REGULAR, PvpScoreUi.date(duel.time), right - inner + slide, bottom + UiTheme.scaled(18f), half - inner,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.46f), vw, vh);
        PvpScoreUi.alpha = 1f;
    }

    /** La note dans son cadre, en couleur — ou pourquoi il n'y en a pas — avec sa confiance en barre. La note défile jusqu'à sa valeur. */
    private void drawScore(UiRenderer r, float top) {
        int vw = screenWidth, vh = screenHeight;
        float dy = enter(0.1f);
        top += dy;
        float bottom = mainBottom + dy;
        double score = duel.score();
        UiColor tone = PvpScoreUi.scoreColor(score);
        PvpScoreUi.tintedCard(r, left, bottom, scoreRight, top, tone, 0.24f, vw, vh);
        float center = (left + scoreRight) / 2f;
        float width = scoreRight - left - UiTheme.scaled(32f);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - UiTheme.scaled(40f), width,
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.46f), vw, vh);
        float body = UiTheme.scaled(0.48f);
        if (score >= 0) {
            String shown = String.valueOf(PvpScoreUi.count(score, progress(0.2f, 1.2f)));
            // Elle grossit d'un cran à son arrivée, puis se pose.
            float pop = PvpScoreUi.pop(openedAt, 0.2f, 0.6f);
            float big = UiTheme.scaled(3.6f) * (0.85f + 0.15f * pop);
            PvpScoreUi.centered(r, UiFont.BOLD, shown, center, top - UiTheme.scaled(160f), width, tone, big, vw, vh);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("sur 100"), center, top - UiTheme.scaled(200f), width,
                UiTheme.TEXT_SECONDARY, body, vw, vh);

            double confidence = duel.report[NativeCombat.REPORT_CONFIDENCE];
            float grow = progress(0.4f, 0.9f);
            float barLeft = left + UiTheme.scaled(36f);
            float barRight = scoreRight - UiTheme.scaled(36f);
            String label = Lang.tr("Confiance") + " " + PvpScoreUi.count(confidence * 100, grow) + " %";
            PvpScoreUi.centered(r, UiFont.REGULAR, label, center, bottom + UiTheme.scaled(46f), width,
                UiTheme.TEXT_PRIMARY, body, vw, vh);
            PvpScoreUi.bar(r, barLeft, bottom + UiTheme.scaled(24f), barRight, UiTheme.scaled(8f), confidence * 100 * grow,
                new UiColor(168, 152, 255, 255), vw, vh);
        } else {
            PvpScoreUi.centered(r, UiFont.BOLD, PvpScoreUi.NO_SCORE, center, top - UiTheme.scaled(150f), width, tone, UiTheme.scaled(3f), vw, vh);
            String[] reason = PvpScoreUi.reasonLines(duel.report[NativeCombat.REPORT_REASON]);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr(reason[0]), center,
                bottom + UiTheme.scaled(64f), width, UiTheme.TEXT_PRIMARY, body, vw, vh);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr(reason[1]), center, bottom + UiTheme.scaled(36f), width,
                UiTheme.TEXT_SECONDARY, body, vw, vh);
        }
        PvpScoreUi.alpha = 1f;
    }

    /**
     * Le face-à-face : en tête, mon jeu seul et le niveau de l'adversaire ;
     * puis une ligne par famille, ma barre vers la gauche, la sienne vers la
     * droite, le nom au milieu. Mes barres sont dans la couleur de leur
     * valeur, les siennes dans la sienne ({@link PvpScoreUi#FOE}). Les lignes
     * arrivent l'une après l'autre, leurs barres poussant depuis le milieu ;
     * au survol, une ligne s'éclaire et son nom grossit un peu.
     *
     * <p>Les zones cliquables restent à leur place pendant l'entrée : seul le
     * dessin bouge, et l'entrée est finie avant qu'on ait le temps de viser.
     */
    private void drawFaceToFace(UiRenderer r, float top, double mouseX, double mouseY) {
        int vw = screenWidth, vh = screenHeight;
        float dy = enter(0.18f);
        float cardLeft = scoreRight + gap();
        PvpScoreUi.card(r, cardLeft, mainBottom + dy, right, top + dy, vw, vh);
        float inner = UiTheme.scaled(26f);
        float center = (cardLeft + right) / 2f;
        float labelHalf = UiTheme.scaled(120f);
        float valueW = UiTheme.scaled(56f);
        float myBarLeft = cardLeft + inner + valueW;
        float myBarRight = center - labelHalf;
        float hisBarLeft = center + labelHalf;
        float hisBarRight = right - inner - valueW;

        // En-tête : mon jeu seul à gauche, son niveau à droite, qui défilent.
        float headerMid = top + dy - faceHeader() / 2f;
        float counting = progress(0.3f, 1f);
        double own = duel.report[NativeCombat.REPORT_OWN];
        double level = duel.report[NativeCombat.REPORT_FAMILIES + NativeCombat.FAMILY_COUNT - 1];
        float label = UiTheme.scaled(0.42f);
        float big = UiTheme.scaled(1.05f);
        opponentHover.setTarget(opponentHit != null && opponentHit.contains(mouseX, mouseY) ? 1f : 0f);
        float oh = opponentHover.get();
        if (oh > 0.01f) {
            PvpScoreUi.rect(r, center, top + dy - faceHeader(), right, top + dy, UiTheme.scaled(10f),
                PvpScoreUi.FOE.withAlpha(0.12f * oh), vw, vh);
        }
        String ownText = String.valueOf(PvpScoreUi.count(own, counting));
        PvpScoreUi.text(r, UiFont.BOLD, ownText, cardLeft + inner, PvpScoreUi.middle(headerMid, big), PvpScoreUi.scoreColor(own), big, vw, vh);
        PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("VOTRE JEU"), cardLeft + inner + r.textWidth(UiFont.BOLD, ownText, big) + UiTheme.scaled(14f),
            PvpScoreUi.middle(headerMid, label), UiTheme.TEXT_SECONDARY, label, vw, vh);
        String levelText = String.valueOf(PvpScoreUi.count(level, counting));
        float levelW = r.textWidth(UiFont.BOLD, levelText, big);
        PvpScoreUi.text(r, UiFont.BOLD, levelText, right - inner - levelW, PvpScoreUi.middle(headerMid, big), PvpScoreUi.FOE, big, vw, vh);
        PvpScoreUi.right(r, UiFont.BOLD, Lang.tr("NIVEAU ADVERSE"), right - inner - levelW - UiTheme.scaled(14f),
            PvpScoreUi.middle(headerMid, label), right - center, UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, oh), label, vw, vh);
        PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("FACE À FACE"), center, PvpScoreUi.middle(headerMid, label * 0.9f),
            labelHalf * 2f - UiTheme.scaled(20f), UiTheme.TEXT_MUTED, label * 0.9f, vw, vh);
        // Le trait sous l'en-tête se trace depuis le milieu.
        float line = progress(0.3f, 0.6f);
        float lineHalf = (right - cardLeft - inner * 2f) / 2f * line;
        PvpScoreUi.rect(r, center - lineHalf, top + dy - faceHeader(), center + lineHalf,
            top + dy - faceHeader() + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, vw, vh);

        float name = UiTheme.scaled(0.5f);
        float value = UiTheme.scaled(0.6f);
        float barH = UiTheme.scaled(10f);
        float cardAlpha = PvpScoreUi.alpha;
        for (int i = 0; i < ROWS; i++) {
            UiWidget hit = familyHits[i];
            float rowIn = progress(0.32f + 0.07f * i, 0.45f);
            PvpScoreUi.alpha = cardAlpha * rowIn;
            float mid = hit.y + hit.h / 2f + dy - (1f - rowIn) * UiTheme.scaled(10f);
            familyHover[i].setTarget(hit.contains(mouseX, mouseY) ? 1f : 0f);
            float hv = familyHover[i].get();
            if (hv > 0.01f) {
                PvpScoreUi.rect(r, hit.x + UiTheme.scaled(8f), mid - hit.h / 2f + UiTheme.scaled(3f), hit.x + hit.w - UiTheme.scaled(8f),
                    mid + hit.h / 2f - UiTheme.scaled(3f), UiTheme.scaled(8f), new UiColor(255, 255, 255, (int) (18 * hv)), vw, vh);
            }
            float grow = progress(0.4f + 0.07f * i, 0.8f);
            // Une famille mesurée sur aucun événement (aucun smash porté, par
            // exemple) vaut la référence dans la note — elle n'y pèse rien —
            // mais afficher 50 se lisait comme une note moyenne gagnée : on
            // montre « - » et un rail vide.
            double mine = duel.report[NativeCombat.REPORT_FAMILIES + i];
            boolean measured = duel.report[NativeCombat.REPORT_MY_COUNTS + i] > 0;
            UiColor tone = measured ? PvpScoreUi.scoreColor(mine) : PvpScoreUi.NEUTRAL;
            PvpScoreUi.text(r, UiFont.BOLD, measured ? String.valueOf(PvpScoreUi.count(mine, grow)) : PvpScoreUi.NO_SCORE,
                cardLeft + inner, PvpScoreUi.middle(mid, value), tone, value, vw, vh);
            PvpScoreUi.barLeftward(r, myBarLeft, mid - barH / 2f, myBarRight - UiTheme.scaled(12f), barH, measured ? mine * grow : 0, tone, vw, vh);

            float nameScale = name * (1f + 0.06f * hv);
            PvpScoreUi.centered(r, hv > 0.5f ? UiFont.BOLD : UiFont.REGULAR, Lang.tr(PvpScoreModule.FAMILIES[i]), center,
                PvpScoreUi.middle(mid, nameScale), labelHalf * 2f - UiTheme.scaled(20f),
                UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hv), nameScale, vw, vh);

            if (i < NativeCombat.FOE_FAMILY_COUNT) {
                double his = duel.report[NativeCombat.REPORT_FOE_FAMILIES + i];
                boolean hisMeasured = duel.report[NativeCombat.REPORT_FOE_COUNTS + i] > 0;
                UiColor hisTone = hisMeasured ? PvpScoreUi.FOE : PvpScoreUi.NEUTRAL;
                PvpScoreUi.bar(r, hisBarLeft + UiTheme.scaled(12f), mid - barH / 2f, hisBarRight, barH, hisMeasured ? his * grow : 0, hisTone, vw, vh);
                PvpScoreUi.right(r, UiFont.BOLD, hisMeasured ? String.valueOf(PvpScoreUi.count(his, grow)) : PvpScoreUi.NO_SCORE,
                    right - inner, PvpScoreUi.middle(mid, value), valueW, hisTone, value, vw, vh);
            } else {
                // Ses ressources ne sont pas mesurées : on le dit plutôt que de laisser un trou.
                PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("non mesuré"), (hisBarLeft + right - inner) / 2f,
                    PvpScoreUi.middle(mid, name * 0.9f), right - inner - hisBarLeft, UiTheme.TEXT_MUTED, name * 0.9f, vw, vh);
            }
        }
        PvpScoreUi.alpha = 1f;
    }

    /** Les chiffres du duel, en cases égales qui arrivent l'une après l'autre : la valeur, son nom dessous. */
    private void drawFigures(UiRenderer r, float top) {
        int vw = screenWidth, vh = screenHeight;
        double[] report = duel.report;
        double charge = report[NativeCombat.REPORT_SMASH_CHARGE];
        float n = progress(0.5f, 1f);
        String[][] figures = {
            { "OPPORTUNITÉS CONVERTIES", PvpScoreUi.count(report[NativeCombat.REPORT_MY_CONVERTED], n) + " / " + PvpScoreUi.count(report[NativeCombat.REPORT_MY_OPENED], n) },
            { "OPPORTUNITÉS SUBIES", PvpScoreUi.count(report[NativeCombat.REPORT_THEIR_CONVERTED], n) + " / " + PvpScoreUi.count(report[NativeCombat.REPORT_THEIR_OPENED], n) },
            { "DÉGÂTS DE SMASH PORTÉS", PvpScoreUi.count(report[NativeCombat.REPORT_MY_SMASH_DEALT], n) + " / " + PvpScoreUi.count(report[NativeCombat.REPORT_MY_SMASH_POSSIBLE], n) },
            { "DÉGÂTS DE SMASH SUBIS", PvpScoreUi.count(report[NativeCombat.REPORT_THEIR_SMASH_DEALT], n) + " / " + PvpScoreUi.count(report[NativeCombat.REPORT_THEIR_SMASH_POSSIBLE], n) },
            { "TEMPS AU CONTACT", PvpScoreUi.duration(report[NativeCombat.REPORT_ENGAGED_SECS] * n) },
            { "CHARGE AU SMASH", charge >= 0 ? PvpScoreUi.count(charge * 100, n) + " %" : PvpScoreUi.NO_SCORE },
            // Le ping est affiché, jamais corrigé.
            { "PING", report[NativeCombat.REPORT_PING] >= 0 ? PvpScoreUi.count(report[NativeCombat.REPORT_PING], n) + " ms" : PvpScoreUi.NO_SCORE },
        };
        float gap = UiTheme.scaled(12f);
        float cell = (right - left - gap * (figures.length - 1)) / figures.length;
        for (int i = 0; i < figures.length; i++) {
            float dy = enter(0.42f + 0.05f * i);
            float x = left + i * (cell + gap);
            float cellTop = top + dy, cellBottom = top - figuresHeight() + dy;
            PvpScoreUi.card(r, x, cellBottom, x + cell, cellTop, vw, vh);
            float center = x + cell / 2f;
            float width = cell - UiTheme.scaled(16f);
            PvpScoreUi.centered(r, UiFont.BOLD, figures[i][1], center, cellBottom + UiTheme.scaled(44f), width,
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.86f), vw, vh);
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr(figures[i][0]), center, cellBottom + UiTheme.scaled(16f), width,
                UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.36f), vw, vh);
        }
        PvpScoreUi.alpha = 1f;
    }

    /**
     * L'analyse : trois cases — points forts, points faibles, à améliorer —
     * qui prennent toute la hauteur restante. Elles sont vides : leur contenu
     * viendra du noyau natif. La place est tenue dès maintenant pour que la
     * page n'ait pas à être refaite le jour où il arrive. Leur liseré se
     * trace de gauche à droite, et « Bientôt disponible » respire doucement.
     */
    private void drawAnalysis(UiRenderer r, float top, float bottom) {
        int vw = screenWidth, vh = screenHeight;
        float cardsTop = top - UiTheme.scaled(34f);
        // Sur une fenêtre trop basse, la bande n'a pas la place d'exister.
        if (cardsTop - bottom < UiTheme.scaled(100f)) {
            return;
        }
        enter(0.6f);
        PvpScoreUi.text(r, UiFont.BOLD, Lang.tr("ANALYSE"), left, top - UiTheme.scaled(18f), UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.48f), vw, vh);
        float gap = UiTheme.scaled(14f);
        float cell = (right - left - gap * (ANALYSIS.length - 1)) / ANALYSIS.length;
        float breathe = 0.65f + 0.35f * (0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1e9 * 2.0));
        for (int i = 0; i < ANALYSIS.length; i++) {
            float dy = enter(0.65f + 0.08f * i);
            float x = left + i * (cell + gap);
            float cTop = cardsTop + dy, cBottom = bottom + dy;
            PvpScoreUi.tintedCard(r, x, cBottom, x + cell, cTop, ANALYSIS_TONES[i], 0.1f, vw, vh);
            float trace = progress(0.75f + 0.08f * i, 0.6f);
            PvpScoreUi.rect(r, x, cTop - UiTheme.scaled(4f), x + cell * trace, cTop, UiTheme.scaled(2f), ANALYSIS_TONES[i], vw, vh);
            float inner = UiTheme.scaled(20f);
            PvpScoreUi.text(r, UiFont.BOLD, Lang.tr(ANALYSIS[i]), x + inner, cTop - UiTheme.scaled(38f), ANALYSIS_TONES[i],
                UiTheme.scaled(0.62f), vw, vh);
            float cardAlpha = PvpScoreUi.alpha;
            PvpScoreUi.alpha = cardAlpha * breathe;
            PvpScoreUi.centered(r, UiFont.REGULAR, Lang.tr("Bientôt disponible"), x + cell / 2f, cBottom + (cTop - cBottom) / 2f - UiTheme.scaled(14f),
                cell - inner * 2f, UiTheme.TEXT_MUTED, UiTheme.scaled(0.48f), vw, vh);
            PvpScoreUi.alpha = cardAlpha;
        }
        PvpScoreUi.alpha = 1f;
    }
}
