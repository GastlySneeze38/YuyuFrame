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
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * L'écran du module « Score PvP » : la moyenne des notes en haut, au centre
 * et en grand, puis les duels, un par ligne — note, résultat, adversaire,
 * date. Un clic sur une ligne ouvre le détail du duel ({@link UiPvpDuelScreen}).
 *
 * <p>L'historique est demandé au noyau natif une fois, à l'ouverture
 * ({@link DuelHistory#load}) : il relit et renote les journaux, ce n'est pas
 * un travail à refaire à chaque image. Rien n'est calculé ici.
 */
public final class UiPvpScoreScreen extends UiScreenBase {

    private static final UiColor OVERLAY = new UiColor(6, 6, 10, 80);
    private static final int GLASS_PASSES = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private final Object lastScreen;
    private final DuelHistory history = DuelHistory.load();
    private UiScrollContainer scroll;
    private float px, py, pw, ph;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;

    public UiPvpScoreScreen(Object lastScreen) {
        super("Score PvP");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
    }

    @Override
    protected UiColor overlayColor() {
        return OVERLAY;
    }

    /** La date d'un duel, ou rien si elle n'est pas connue. */
    static String date(long millis) {
        return millis > 0 ? DATE.format(Instant.ofEpochMilli(millis)) : "";
    }

    /** La couleur d'un résultat : vert, rouge, ou neutre pour un nul. */
    static UiColor resultColor(int result) {
        return result == 1 ? UiTheme.SUCCESS : result == 2 ? UiTheme.DANGER : UiTheme.TEXT_SECONDARY;
    }

    static String resultText(int result) {
        return result >= 0 && result < PvpScoreModule.RESULTS.length ? Lang.tr(PvpScoreModule.RESULTS[result]) : "";
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

            float pad = UiTheme.scaled(20f);
            renderer.drawText(UiFont.BOLD, Lang.tr("Score PvP"), px + pad, py + ph - UiTheme.scaled(30f),
                UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.5f), screenWidth, screenHeight);
            drawAverage(renderer);

            if (history.duels.isEmpty()) {
                String empty = Lang.tr("Aucun duel pour l'instant — ils apparaîtront ici dès le premier terminé.");
                float scale = UiTheme.scaled(0.46f);
                renderer.drawText(empty, px + (pw - renderer.textWidth(empty, scale)) / 2f, py + ph * 0.35f,
                    UiTheme.TEXT_MUTED, scale, screenWidth, screenHeight);
            } else if (scroll != null) {
                scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] uiDraw: " + t);
        }
    }

    /** La moyenne, en grand et centrée, avec ce sur quoi elle repose juste en dessous. */
    private void drawAverage(UiRenderer renderer) {
        float big = UiTheme.scaled(1.9f);
        float small = UiTheme.scaled(0.44f);
        String value = history.average >= 0 ? String.valueOf(Math.round(history.average)) : "—";
        float valueWidth = renderer.textWidth(UiFont.BOLD, value, big);
        float top = py + ph - UiTheme.scaled(96f);
        renderer.drawText(UiFont.BOLD, value, px + (pw - valueWidth) / 2f, top,
            UiTheme.TEXT_PRIMARY, big, screenWidth, screenHeight);

        String caption = history.scored == 0
            ? Lang.tr("Pas encore de duel noté")
            : Lang.tr("Note moyenne") + "  ·  " + history.scored + " " + Lang.tr(history.scored > 1 ? "duels notés" : "duel noté");
        renderer.drawText(caption, px + (pw - renderer.textWidth(caption, small)) / 2f, top - UiTheme.scaled(24f),
            UiTheme.TEXT_SECONDARY, small, screenWidth, screenHeight);
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();
        pw = Math.min(UiTheme.scaled(640f), screenWidth - UiTheme.scaled(80f));
        ph = Math.min(UiTheme.scaled(560f), screenHeight - UiTheme.scaled(120f));
        px = (screenWidth - pw) / 2f;
        py = (screenHeight - ph) / 2f + UiTheme.scaled(16f);

        // Sous le panneau, pas dedans : les widgets de l'écran sont dessinés
        // avant le panneau, qui recouvrirait le bouton.
        float buttonW = UiTheme.scaled(120f);
        float buttonH = UiTheme.scaled(30f);
        widgets.add(new UiButton(px + (pw - buttonW) / 2f, py - buttonH - UiTheme.scaled(12f), buttonW, buttonH,
            Lang.tr("Retour"), () -> closeTo(lastScreen)));

        float pad = UiTheme.scaled(20f);
        float header = UiTheme.scaled(150f);
        float rowH = UiTheme.scaled(38f);
        float gap = UiTheme.scaled(6f);
        float vpX = px + pad;
        float vpW = pw - pad * 2f;
        float vpH = ph - header - pad;
        scroll = new UiScrollContainer(vpX, py + pad, vpW, Math.max(1f, vpH));
        float rowW = vpW - UiTheme.scaled(16f);
        for (int i = 0; i < history.duels.size(); i++) {
            DuelHistory.Duel duel = history.duels.get(i);
            scroll.add(new DuelRow(vpX, -(i * (rowH + gap)) - rowH, rowW, rowH, duel,
                () -> closeTo(new UiPvpDuelScreen(this, duel))));
        }
    }

    /**
     * Un duel de la liste, tout sur une ligne : la note, le résultat,
     * l'adversaire, puis la date à droite.
     */
    private static final class DuelRow extends UiWidget {

        private final DuelHistory.Duel duel;
        private final Runnable open;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);

        DuelRow(float x, float y, float w, float h, DuelHistory.Duel duel, Runnable open) {
            super(x, y, w, h);
            this.duel = duel;
            this.open = open;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover.get()), vpWidth, vpHeight);

            float pad = UiTheme.scaled(14f);
            float baseline = y + h / 2f - UiTheme.scaled(6f);
            float normal = UiTheme.scaled(0.5f);
            float strong = UiTheme.scaled(0.62f);

            // La note, dans une colonne de largeur fixe pour que les lignes s'alignent.
            String score = duel.score() >= 0 ? String.valueOf(Math.round(duel.score())) : "—";
            renderer.drawText(UiFont.BOLD, score, x + pad, baseline - UiTheme.scaled(1f),
                duel.score() >= 0 ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, strong, vpWidth, vpHeight);

            float column = x + pad + UiTheme.scaled(54f);
            String result = resultText(duel.result());
            renderer.drawText(result, column, baseline, resultColor(duel.result()), normal, vpWidth, vpHeight);

            column += UiTheme.scaled(96f);
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            renderer.drawText(opponent, column, baseline, UiTheme.TEXT_PRIMARY, normal, vpWidth, vpHeight);

            String date = date(duel.time);
            renderer.drawText(date, x + w - pad - renderer.textWidth(date, UiTheme.scaled(0.44f)), baseline,
                UiTheme.TEXT_MUTED, UiTheme.scaled(0.44f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            open.run();
        }
    }
}
