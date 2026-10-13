package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;

/**
 * La page d'un duel, ouverte depuis l'historique de {@link UiPvpScoreScreen}.
 *
 * <p>Quatre bandes, de haut en bas :
 * <ol>
 *   <li>le bandeau, teinté par le résultat : l'adversaire, la date, le résultat ;</li>
 *   <li>la note, en grand et en couleur, à côté des six familles — chaque
 *       barre dans la couleur de sa valeur ;</li>
 *   <li>les chiffres du duel : opportunités, temps au contact, ping ;</li>
 *   <li>l'analyse — points forts, points faibles, à améliorer. <b>La place
 *       est réservée, le contenu n'existe pas encore</b> : il viendra du
 *       noyau natif, comme le reste ({@link #ANALYSIS}).</li>
 * </ol>
 *
 * <p>Tout vient du résumé calculé par le noyau — rien n'est calculé ici.
 */
public final class UiPvpDuelScreen extends UiScreenBase {

    private static final UiColor OVERLAY = new UiColor(6, 6, 10, 120);
    private static final int GLASS_PASSES = 3;

    /** Les trois cases de l'analyse, et la couleur de leur titre. */
    private static final String[] ANALYSIS = { "Points forts", "Points faibles", "À améliorer" };

    private final Object lastScreen;
    private final DuelHistory.Duel duel;
    private float px, py, pw, ph;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;

    public UiPvpDuelScreen(Object lastScreen, DuelHistory.Duel duel) {
        super("Duel");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.duel = duel;
    }

    @Override
    protected UiColor overlayColor() {
        return OVERLAY;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastW || screenHeight != lastH || UiTheme.UI_SCALE != lastScale)) {
            widgets.clear();
            float[] page = UiPvpScoreScreen.pageBounds(screenWidth, screenHeight);
            px = page[0]; py = page[1]; pw = page[2]; ph = page[3];
            widgets.add(UiPvpScoreScreen.backButton(px, py, pw, () -> closeTo(lastScreen)));
            lastW = screenWidth; lastH = screenHeight; lastScale = UiTheme.UI_SCALE;
        }
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            renderer.drawGlassPanel(px, py, px + pw, py + ph, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, UiTheme.PANEL_BG,
                UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), screenWidth, screenHeight);
            float pad = UiTheme.scaled(28f);
            float left = px + pad;
            float right = px + pw - pad;
            float gap = UiTheme.scaled(18f);
            float y = drawBanner(renderer, left, right, py + ph - pad);
            y = drawScoreAndFamilies(renderer, left, right, y - gap);
            y = drawFigures(renderer, left, right, y - gap);
            drawAnalysis(renderer, left, right, y - gap, py + pad);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpDuelScreen] uiDraw: " + t);
        }
    }

    /** Le bandeau, teinté par le résultat : l'adversaire et la date à gauche, le résultat à droite. Rend son bas. */
    private float drawBanner(UiRenderer r, float left, float right, float top) {
        float height = UiTheme.scaled(74f);
        float bottom = top - height;
        UiColor tone = UiPvpScoreScreen.resultColor(duel.result());
        r.drawRoundedRect(left, bottom, right, top, UiTheme.RADIUS_SM, UiColor.lerp(UiTheme.CARD_BG, tone, 0.14f), screenWidth, screenHeight);
        r.drawRoundedRect(left, bottom, left + UiTheme.scaled(5f), top, UiTheme.scaled(2f), tone, screenWidth, screenHeight);

        float inner = UiTheme.scaled(22f);
        float title = UiTheme.scaled(0.72f);
        r.drawText(Lang.tr("CONTRE"), left + inner, top - UiTheme.scaled(24f), UiTheme.TEXT_MUTED, UiTheme.scaled(0.36f), screenWidth, screenHeight);
        String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
        r.drawText(UiFont.BOLD, opponent, left + inner, top - UiTheme.scaled(48f), UiTheme.TEXT_PRIMARY, title, screenWidth, screenHeight);

        String result = UiPvpScoreScreen.resultText(duel.result()).toUpperCase(java.util.Locale.ROOT);
        float resultScale = UiTheme.scaled(0.9f);
        r.drawText(UiFont.BOLD, result, right - inner - r.textWidth(UiFont.BOLD, result, resultScale), top - UiTheme.scaled(40f),
            tone, resultScale, screenWidth, screenHeight);
        String date = UiPvpScoreScreen.date(duel.time);
        float small = UiTheme.scaled(0.42f);
        r.drawText(date, right - inner - r.textWidth(date, small), top - UiTheme.scaled(60f), UiTheme.TEXT_MUTED, small, screenWidth, screenHeight);
        return bottom;
    }

    /**
     * La note dans un cadre à gauche — en couleur, ou la raison de son
     * absence — et les six familles à droite. Rend le bas de la bande.
     */
    private float drawScoreAndFamilies(UiRenderer r, float left, float right, float top) {
        float row = UiTheme.scaled(30f);
        float height = NativeCombat.FAMILY_COUNT * row + UiTheme.scaled(16f);
        float bottom = top - height;
        float small = UiTheme.scaled(0.46f);

        float boxW = UiTheme.scaled(210f);
        double score = duel.score();
        UiColor tone = UiPvpScoreScreen.scoreColor(score);
        r.drawRoundedRect(left, bottom, left + boxW, top, UiTheme.RADIUS_SM, UiColor.lerp(UiTheme.CARD_BG, tone, 0.10f), screenWidth, screenHeight);
        float center = left + boxW / 2f;
        centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - UiTheme.scaled(26f), UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f));
        if (score >= 0) {
            centered(r, UiFont.BOLD, UiPvpScoreScreen.scoreText(score), center, bottom + height * 0.40f, tone, UiTheme.scaled(2.2f));
            centered(r, null, Lang.tr("sur 100"), center, bottom + height * 0.26f, UiTheme.TEXT_MUTED, small);
            String confidence = Lang.tr("Confiance") + " " + Math.round(duel.report[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
            centered(r, null, confidence, center, bottom + UiTheme.scaled(16f), UiTheme.TEXT_SECONDARY, small);
        } else {
            centered(r, UiFont.BOLD, UiPvpScoreScreen.NO_SCORE, center, bottom + height * 0.48f, tone, UiTheme.scaled(1.6f));
            boolean tooShort = duel.report[NativeCombat.REPORT_REASON] == 1;
            centered(r, null, Lang.tr(tooShort ? "Duel trop court" : "Trop peu d'opportunités"), center, bottom + height * 0.28f, UiTheme.TEXT_SECONDARY, small);
            centered(r, null, Lang.tr("pour être noté"), center, bottom + height * 0.28f - UiTheme.scaled(18f), UiTheme.TEXT_MUTED, small);
        }

        // Les familles : libellé, barre dans la couleur de sa valeur, valeur.
        float cardLeft = left + boxW + UiTheme.scaled(18f);
        r.drawRoundedRect(cardLeft, bottom, right, top, UiTheme.RADIUS_SM, UiTheme.CARD_BG, screenWidth, screenHeight);
        float inner = UiTheme.scaled(22f);
        float labelW = UiTheme.scaled(150f);
        float valueW = UiTheme.scaled(40f);
        float barLeft = cardLeft + inner + labelW;
        float barRight = right - inner - valueW;
        float y = top - UiTheme.scaled(8f);
        for (int i = 0; i < NativeCombat.FAMILY_COUNT; i++) {
            y -= row;
            double family = Math.max(0, Math.min(100, duel.report[NativeCombat.REPORT_FAMILIES + i]));
            // Le niveau de l'adversaire parle de lui, pas de nous : gris, pour
            // qu'il ne se lise pas comme une de nos familles.
            boolean opponent = i == NativeCombat.FAMILY_COUNT - 1;
            UiColor familyTone = opponent ? UiTheme.TEXT_MUTED : UiPvpScoreScreen.scoreColor(family);
            float baseline = y + row / 2f - UiTheme.scaled(5f);
            r.drawText(Lang.tr(PvpScoreModule.FAMILIES[i]), cardLeft + inner, baseline,
                opponent ? UiTheme.TEXT_MUTED : UiTheme.TEXT_SECONDARY, small, screenWidth, screenHeight);
            float barY = y + row / 2f - UiTheme.scaled(4f);
            float barTop = barY + UiTheme.scaled(8f);
            r.drawRoundedRect(barLeft, barY, barRight, barTop, UiTheme.scaled(4f), UiTheme.PANEL_BG, screenWidth, screenHeight);
            float filled = barLeft + (barRight - barLeft) * (float) (family / 100.0);
            if (filled > barLeft + 1f) {
                r.drawRoundedRect(barLeft, barY, filled, barTop, UiTheme.scaled(4f), familyTone, screenWidth, screenHeight);
            }
            String value = String.valueOf(Math.round(family));
            r.drawText(UiFont.BOLD, value, right - inner - r.textWidth(UiFont.BOLD, value, small), baseline,
                familyTone, small, screenWidth, screenHeight);
        }
        return bottom;
    }

    /** Les chiffres du duel, en quatre cases égales. Rend le bas de la bande. */
    private float drawFigures(UiRenderer r, float left, float right, float top) {
        float height = UiTheme.scaled(66f);
        float bottom = top - height;
        double[] report = duel.report;
        String[][] figures = {
            { "OPPORTUNITÉS CONVERTIES", (int) report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_MY_OPENED] },
            { "OPPORTUNITÉS SUBIES", (int) report[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_THEIR_OPENED] },
            { "TEMPS AU CONTACT", duration(report[NativeCombat.REPORT_ENGAGED_SECS]) },
            { "PING", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : UiPvpScoreScreen.NO_SCORE },
        };
        float gap = UiTheme.scaled(12f);
        float cell = (right - left - gap * (figures.length - 1)) / figures.length;
        for (int i = 0; i < figures.length; i++) {
            float x = left + i * (cell + gap);
            r.drawRoundedRect(x, bottom, x + cell, top, UiTheme.RADIUS_SM, UiTheme.CARD_BG, screenWidth, screenHeight);
            float center = x + cell / 2f;
            centered(r, UiFont.BOLD, figures[i][1], center, bottom + UiTheme.scaled(30f), UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.66f));
            centered(r, null, Lang.tr(figures[i][0]), center, bottom + UiTheme.scaled(12f), UiTheme.TEXT_MUTED, UiTheme.scaled(0.34f));
        }
        return bottom;
    }

    /**
     * L'analyse : trois cases — points forts, points faibles, à améliorer —
     * qui prennent toute la hauteur restante. Elles sont vides : leur contenu
     * viendra du noyau natif. La place est tenue dès maintenant pour que la
     * page n'ait pas à être refaite le jour où il arrive.
     */
    private void drawAnalysis(UiRenderer r, float left, float right, float top, float bottom) {
        float title = UiTheme.scaled(0.42f);
        r.drawText(UiFont.BOLD, Lang.tr("ANALYSE"), left, top - UiTheme.scaled(14f), UiTheme.TEXT_MUTED, title, screenWidth, screenHeight);
        float cardsTop = top - UiTheme.scaled(26f);
        // Sur une fenêtre trop basse, la bande n'a pas la place d'exister.
        if (cardsTop - bottom < UiTheme.scaled(70f)) {
            return;
        }
        UiColor[] tones = { UiTheme.SUCCESS, UiTheme.DANGER, UiTheme.ACCENT };
        float gap = UiTheme.scaled(12f);
        float cell = (right - left - gap * (ANALYSIS.length - 1)) / ANALYSIS.length;
        for (int i = 0; i < ANALYSIS.length; i++) {
            float x = left + i * (cell + gap);
            r.drawRoundedRect(x, bottom, x + cell, cardsTop, UiTheme.RADIUS_SM, UiTheme.CARD_BG, screenWidth, screenHeight);
            r.drawRoundedRect(x, cardsTop - UiTheme.scaled(3f), x + cell, cardsTop, UiTheme.scaled(2f), tones[i], screenWidth, screenHeight);
            float inner = UiTheme.scaled(16f);
            r.drawText(UiFont.BOLD, Lang.tr(ANALYSIS[i]), x + inner, cardsTop - UiTheme.scaled(28f), tones[i], UiTheme.scaled(0.5f), screenWidth, screenHeight);
            centered(r, null, Lang.tr("Bientôt disponible"), x + cell / 2f, bottom + (cardsTop - bottom) / 2f - UiTheme.scaled(12f),
                UiTheme.TEXT_MUTED, UiTheme.scaled(0.42f));
        }
    }

    private static String duration(double seconds) {
        long total = Math.round(seconds);
        return total >= 60 ? (total / 60) + " min " + (total % 60) + " s" : total + " s";
    }

    /** Un texte centré sur {@code center} ; {@code font} nul = la police ordinaire. */
    private void centered(UiRenderer r, UiFont font, String text, float center, float y, UiColor color, float scale) {
        if (font == null) {
            r.drawText(text, center - r.textWidth(text, scale) / 2f, y, color, scale, screenWidth, screenHeight);
        } else {
            r.drawText(font, text, center - r.textWidth(font, text, scale) / 2f, y, color, scale, screenWidth, screenHeight);
        }
    }
}
