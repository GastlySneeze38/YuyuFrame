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
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;

/**
 * Le détail d'un duel, ouvert depuis la liste de {@link UiPvpScoreScreen}.
 *
 * <p>Trois bandes, de haut en bas : qui, quand et le résultat ; la note à
 * gauche et les six familles à droite ; puis les chiffres du duel
 * (opportunités, ping, temps au contact). Tout vient du résumé calculé par
 * le noyau natif — rien n'est calculé ici.
 */
public final class UiPvpDuelScreen extends UiScreenBase {

    private static final UiColor OVERLAY = new UiColor(6, 6, 10, 80);
    private static final int GLASS_PASSES = 3;

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
            buildLayout();
            lastW = screenWidth; lastH = screenHeight; lastScale = UiTheme.UI_SCALE;
        }
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            renderer.drawGlassPanel(px, py, px + pw, py + ph, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, UiTheme.PANEL_BG,
                UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), screenWidth, screenHeight);
            float pad = UiTheme.scaled(24f);
            float top = py + ph - pad;
            top = drawHeader(renderer, px + pad, px + pw - pad, top);
            top = drawScoreAndFamilies(renderer, px + pad, px + pw - pad, top - UiTheme.scaled(22f));
            drawFigures(renderer, px + pad, px + pw - pad, top - UiTheme.scaled(22f));
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpDuelScreen] uiDraw: " + t);
        }
    }

    /** L'adversaire à gauche, le résultat à droite, la date dessous. Rend le bas de la bande. */
    private float drawHeader(UiRenderer r, float left, float right, float top) {
        float title = UiTheme.scaled(0.62f);
        float small = UiTheme.scaled(0.44f);
        float y = top - UiTheme.scaled(20f);
        String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : Lang.tr("Contre") + " " + duel.opponent;
        r.drawText(UiFont.BOLD, opponent, left, y, UiTheme.TEXT_PRIMARY, title, screenWidth, screenHeight);
        String result = UiPvpScoreScreen.resultText(duel.result());
        r.drawText(UiFont.BOLD, result, right - r.textWidth(UiFont.BOLD, result, title), y,
            UiPvpScoreScreen.resultColor(duel.result()), title, screenWidth, screenHeight);
        y -= UiTheme.scaled(22f);
        r.drawText(UiPvpScoreScreen.date(duel.time), left, y, UiTheme.TEXT_MUTED, small, screenWidth, screenHeight);
        y -= UiTheme.scaled(12f);
        separator(r, left, right, y);
        return y;
    }

    /**
     * La note dans un cadre à gauche — ou la raison de son absence — et les
     * six familles à droite, une barre chacune. Rend le bas de la bande.
     */
    private float drawScoreAndFamilies(UiRenderer r, float left, float right, float top) {
        float row = UiTheme.scaled(26f);
        float height = NativeCombat.FAMILY_COUNT * row;
        float bottom = top - height;
        float small = UiTheme.scaled(0.44f);

        // Le cadre de la note.
        float boxW = UiTheme.scaled(170f);
        r.drawRoundedRect(left, bottom, left + boxW, top, UiTheme.RADIUS_SM, UiTheme.CARD_BG, screenWidth, screenHeight);
        double score = duel.score();
        float center = left + boxW / 2f;
        if (score >= 0) {
            float big = UiTheme.scaled(1.7f);
            String value = String.valueOf(Math.round(score));
            r.drawText(UiFont.BOLD, value, center - r.textWidth(UiFont.BOLD, value, big) / 2f, bottom + height * 0.44f,
                UiTheme.TEXT_PRIMARY, big, screenWidth, screenHeight);
            centered(r, Lang.tr("sur 100"), center, bottom + height * 0.30f, UiTheme.TEXT_MUTED, small);
            String confidence = Lang.tr("Confiance") + " " + Math.round(duel.report[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
            centered(r, confidence, center, bottom + UiTheme.scaled(14f), UiTheme.TEXT_SECONDARY, small);
        } else {
            centered(r, "—", center, bottom + height * 0.52f, UiTheme.TEXT_MUTED, UiTheme.scaled(1.2f));
            boolean tooShort = duel.report[NativeCombat.REPORT_REASON] == 1;
            centered(r, Lang.tr(tooShort ? "Duel trop court" : "Trop peu d'opportunités"), center, bottom + height * 0.30f, UiTheme.TEXT_SECONDARY, small);
            centered(r, Lang.tr("pour être noté"), center, bottom + height * 0.30f - UiTheme.scaled(16f), UiTheme.TEXT_MUTED, small);
        }

        // Les familles : libellé, barre, valeur.
        float familiesLeft = left + boxW + UiTheme.scaled(26f);
        float labelW = UiTheme.scaled(128f);
        float valueW = UiTheme.scaled(34f);
        float barLeft = familiesLeft + labelW;
        float barRight = right - valueW;
        float y = top;
        for (int i = 0; i < NativeCombat.FAMILY_COUNT; i++) {
            y -= row;
            double family = Math.max(0, Math.min(100, duel.report[NativeCombat.REPORT_FAMILIES + i]));
            // Le niveau de l'adversaire parle de lui, pas de nous : il est écrit
            // en retrait, pour ne pas se lire comme une de nos familles.
            boolean opponent = i == NativeCombat.FAMILY_COUNT - 1;
            float baseline = y + row / 2f - UiTheme.scaled(5f);
            r.drawText(Lang.tr(PvpScoreModule.FAMILIES[i]), familiesLeft, baseline,
                opponent ? UiTheme.TEXT_MUTED : UiTheme.TEXT_SECONDARY, small, screenWidth, screenHeight);
            float barY = y + row / 2f - UiTheme.scaled(3f);
            float barTop = barY + UiTheme.scaled(6f);
            r.drawRoundedRect(barLeft, barY, barRight, barTop, UiTheme.scaled(3f), UiTheme.CARD_BG, screenWidth, screenHeight);
            float filled = barLeft + (barRight - barLeft) * (float) (family / 100.0);
            if (filled > barLeft + 1f) {
                r.drawRoundedRect(barLeft, barY, filled, barTop, UiTheme.scaled(3f),
                    opponent ? UiTheme.TEXT_MUTED : UiTheme.ACCENT, screenWidth, screenHeight);
            }
            String value = String.valueOf(Math.round(family));
            r.drawText(value, right - r.textWidth(value, small), baseline, UiTheme.TEXT_PRIMARY, small, screenWidth, screenHeight);
        }
        return bottom;
    }

    /** Les chiffres du duel, en quatre cases égales. */
    private void drawFigures(UiRenderer r, float left, float right, float top) {
        separator(r, left, right, top + UiTheme.scaled(10f));
        double[] report = duel.report;
        String[][] figures = {
            { "Opportunités converties", (int) report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_MY_OPENED] },
            { "Opportunités subies", (int) report[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_THEIR_OPENED] },
            { "Temps au contact", duration(report[NativeCombat.REPORT_ENGAGED_SECS]) },
            { "Ping", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : "—" },
        };
        float cell = (right - left) / figures.length;
        float small = UiTheme.scaled(0.42f);
        float strong = UiTheme.scaled(0.6f);
        for (int i = 0; i < figures.length; i++) {
            float center = left + cell * (i + 0.5f);
            float valueW = r.textWidth(UiFont.BOLD, figures[i][1], strong);
            r.drawText(UiFont.BOLD, figures[i][1], center - valueW / 2f, top - UiTheme.scaled(22f),
                UiTheme.TEXT_PRIMARY, strong, screenWidth, screenHeight);
            centered(r, Lang.tr(figures[i][0]), center, top - UiTheme.scaled(42f), UiTheme.TEXT_MUTED, small);
        }
    }

    private static String duration(double seconds) {
        long total = Math.round(seconds);
        return total >= 60 ? (total / 60) + " min " + (total % 60) + " s" : total + " s";
    }

    private void centered(UiRenderer r, String text, float center, float y, UiColor color, float scale) {
        r.drawText(text, center - r.textWidth(text, scale) / 2f, y, color, scale, screenWidth, screenHeight);
    }

    private void separator(UiRenderer r, float left, float right, float y) {
        r.drawRoundedRect(left, y, right, y + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, screenWidth, screenHeight);
    }

    private void buildLayout() {
        widgets.clear();
        pw = Math.min(UiTheme.scaled(640f), screenWidth - UiTheme.scaled(80f));
        ph = Math.min(UiTheme.scaled(360f), screenHeight - UiTheme.scaled(120f));
        px = (screenWidth - pw) / 2f;
        py = (screenHeight - ph) / 2f + UiTheme.scaled(16f);

        // Sous le panneau, pas dedans : les widgets de l'écran sont dessinés
        // avant le panneau, qui recouvrirait le bouton.
        float buttonW = UiTheme.scaled(120f);
        float buttonH = UiTheme.scaled(30f);
        widgets.add(new UiButton(px + (pw - buttonW) / 2f, py - buttonH - UiTheme.scaled(12f), buttonW, buttonH,
            Lang.tr("Retour"), () -> closeTo(lastScreen)));
    }
}
