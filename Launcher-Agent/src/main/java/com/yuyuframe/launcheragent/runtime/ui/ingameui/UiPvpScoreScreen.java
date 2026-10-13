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
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * La page du module « Score PvP » : un bandeau centré — la note moyenne en
 * grand, colorée selon qu'elle est bonne ou non, et le bilan des duels — puis
 * l'historique, une ligne par duel, teintée par son résultat. Un clic sur une
 * ligne ouvre la page du duel ({@link UiPvpDuelScreen}).
 *
 * <p>L'historique est demandé au noyau natif une fois, à l'ouverture
 * ({@link DuelHistory#load}) : il relit et renote les journaux, ce n'est pas
 * un travail à refaire à chaque image. Rien n'est calculé ici, hormis le
 * décompte des victoires et des défaites de la liste affichée.
 */
public final class UiPvpScoreScreen extends UiScreenBase {

    private static final UiColor OVERLAY = new UiColor(6, 6, 10, 120);
    private static final int GLASS_PASSES = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    /** Une note « moyenne » : ni bonne ni mauvaise. Le vert et le rouge sont ceux du thème. */
    private static final UiColor AMBER = new UiColor(226, 176, 72, 255);
    /** À partir de là, une note est bonne ; en dessous de l'autre seuil, elle est mauvaise. */
    private static final double GOOD = 65;
    private static final double POOR = 45;

    /** Ce qui tient lieu de note quand il n'y en a pas (la police n'a pas de tiret long). */
    public static final String NO_SCORE = "-";

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

    // ── Ce que les deux pages partagent ──────────────────────────────────────

    /** La date d'un duel, ou rien si elle n'est pas connue. */
    static String date(long millis) {
        return millis > 0 ? DATE.format(Instant.ofEpochMilli(millis)) : "";
    }

    /** La couleur d'un résultat : vert, rouge, ou neutre pour un nul. */
    public static UiColor resultColor(int result) {
        return result == 1 ? UiTheme.SUCCESS : result == 2 ? UiTheme.DANGER : UiTheme.TEXT_SECONDARY;
    }

    public static String resultText(int result) {
        return result >= 0 && result < PvpScoreModule.RESULTS.length ? Lang.tr(PvpScoreModule.RESULTS[result]) : "";
    }

    /** La couleur d'une note sur 100 : verte si elle est bonne, ambre si elle est moyenne, rouge sinon ; grise sans note. */
    public static UiColor scoreColor(double score) {
        if (score < 0) return UiTheme.TEXT_MUTED;
        return score >= GOOD ? UiTheme.SUCCESS : score >= POOR ? AMBER : UiTheme.DANGER;
    }

    public static String scoreText(double score) {
        return score >= 0 ? String.valueOf(Math.round(score)) : NO_SCORE;
    }

    /** Le cadre commun aux deux pages : presque tout l'écran, centré, le bouton de retour dessous. */
    static float[] pageBounds(int screenWidth, int screenHeight) {
        float w = Math.min(UiTheme.scaled(900f), screenWidth - UiTheme.scaled(120f));
        float h = Math.min(UiTheme.scaled(720f), screenHeight - UiTheme.scaled(130f));
        return new float[]{ (screenWidth - w) / 2f, (screenHeight - h) / 2f + UiTheme.scaled(20f), w, h };
    }

    static UiButton backButton(float px, float py, float pw, Runnable back) {
        // Sous le panneau, pas dedans : les widgets de l'écran sont dessinés
        // avant le panneau, qui recouvrirait le bouton.
        float w = UiTheme.scaled(130f);
        float h = UiTheme.scaled(32f);
        return new UiButton(px + (pw - w) / 2f, py - h - UiTheme.scaled(14f), w, h, Lang.tr("Retour"), back);
    }

    // ── La page ──────────────────────────────────────────────────────────────

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
            drawHeader(renderer);
            if (history.duels.isEmpty()) {
                String empty = Lang.tr("Aucun duel pour l'instant : ils apparaîtront ici dès le premier terminé.");
                float scale = UiTheme.scaled(0.48f);
                renderer.drawText(empty, px + (pw - renderer.textWidth(empty, scale)) / 2f, py + ph * 0.4f,
                    UiTheme.TEXT_MUTED, scale, screenWidth, screenHeight);
            } else if (scroll != null) {
                scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiPvpScoreScreen] uiDraw: " + t);
        }
    }

    /**
     * Le bandeau : le nom de la page, la note moyenne en grand et en couleur,
     * puis le bilan — victoires, défaites, duels notés.
     */
    private void drawHeader(UiRenderer r) {
        float center = px + pw / 2f;
        float top = py + ph - UiTheme.scaled(34f);
        float label = UiTheme.scaled(0.46f);
        centered(r, UiFont.BOLD, Lang.tr("NOTE MOYENNE"), center, top, UiTheme.TEXT_MUTED, label);

        float big = UiTheme.scaled(2.3f);
        centered(r, UiFont.BOLD, scoreText(history.average), center, top - UiTheme.scaled(76f), scoreColor(history.average), big);

        int wins = 0, losses = 0;
        for (DuelHistory.Duel duel : history.duels) {
            if (duel.result() == 1) wins++;
            else if (duel.result() == 2) losses++;
        }
        // Trois chiffres côte à côte, chacun dans sa couleur.
        float small = UiTheme.scaled(0.48f);
        String winsText = wins + " " + Lang.tr(wins > 1 ? "victoires" : "victoire");
        String lossesText = losses + " " + Lang.tr(losses > 1 ? "défaites" : "défaite");
        String scoredText = history.scored + " " + Lang.tr(history.scored > 1 ? "duels notés" : "duel noté");
        float gap = UiTheme.scaled(26f);
        float total = r.textWidth(winsText, small) + r.textWidth(lossesText, small) + r.textWidth(scoredText, small) + gap * 2f;
        float x = center - total / 2f;
        float y = top - UiTheme.scaled(108f);
        r.drawText(winsText, x, y, UiTheme.SUCCESS, small, screenWidth, screenHeight);
        x += r.textWidth(winsText, small) + gap;
        r.drawText(lossesText, x, y, UiTheme.DANGER, small, screenWidth, screenHeight);
        x += r.textWidth(lossesText, small) + gap;
        r.drawText(scoredText, x, y, UiTheme.TEXT_SECONDARY, small, screenWidth, screenHeight);

        // Le titre de la liste, et un trait pour la détacher du bandeau.
        float pad = UiTheme.scaled(28f);
        float line = top - UiTheme.scaled(138f);
        r.drawRoundedRect(px + pad, line, px + pw - pad, line + Math.max(1f, UiTheme.scaled(1f)), 0f,
            UiTheme.GLASS_BORDER, screenWidth, screenHeight);
        r.drawText(UiFont.BOLD, Lang.tr("HISTORIQUE DES DUELS"), px + pad, line - UiTheme.scaled(22f),
            UiTheme.TEXT_MUTED, UiTheme.scaled(0.42f), screenWidth, screenHeight);
    }

    private void centered(UiRenderer r, UiFont font, String text, float center, float y, UiColor color, float scale) {
        r.drawText(font, text, center - r.textWidth(font, text, scale) / 2f, y, color, scale, screenWidth, screenHeight);
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();
        float[] page = pageBounds(screenWidth, screenHeight);
        px = page[0]; py = page[1]; pw = page[2]; ph = page[3];
        widgets.add(backButton(px, py, pw, () -> closeTo(lastScreen)));

        float pad = UiTheme.scaled(28f);
        float header = UiTheme.scaled(204f);
        float rowH = UiTheme.scaled(48f);
        float gap = UiTheme.scaled(6f);
        float vpX = px + pad;
        float vpW = pw - pad * 2f;
        float vpH = ph - header - UiTheme.scaled(18f);
        scroll = new UiScrollContainer(vpX, py + UiTheme.scaled(18f), vpW, Math.max(1f, vpH));
        float rowW = vpW - UiTheme.scaled(16f);
        for (int i = 0; i < history.duels.size(); i++) {
            DuelHistory.Duel duel = history.duels.get(i);
            scroll.add(new DuelRow(vpX, -(i * (rowH + gap)) - rowH, rowW, rowH, duel,
                () -> closeTo(new UiPvpDuelScreen(this, duel))));
        }
    }

    /**
     * Un duel de l'historique, tout sur une ligne teintée par son résultat :
     * la note dans une pastille de sa couleur, le résultat, l'adversaire, les
     * opportunités converties, la date.
     *
     * <p>Chaque couleur passe par {@link #faded} : sur les versions récentes
     * du jeu, la liste ne découpe pas ce qui dépasse de son cadre, c'est à
     * chaque ligne de s'estomper à ses bords ({@link UiWidget#clipFade}).
     * Sans cela, les lignes débordaient sous le panneau.
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

        private UiColor faded(UiColor color) {
            return clipFade >= 1f ? color : color.multiplyAlpha(clipFade);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor tone = resultColor(duel.result());
            // Le fond prend un peu de la couleur du résultat, davantage au survol.
            UiColor background = UiColor.lerp(UiTheme.CARD_BG, tone, 0.10f + 0.10f * hover.get());
            r.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, faded(background), vpWidth, vpHeight);
            r.drawRoundedRect(x, y, x + UiTheme.scaled(4f), y + h, UiTheme.scaled(2f), faded(tone), vpWidth, vpHeight);

            float pad = UiTheme.scaled(18f);
            float baseline = y + h / 2f - UiTheme.scaled(6f);
            float normal = UiTheme.scaled(0.52f);
            float small = UiTheme.scaled(0.42f);

            // La note, dans une pastille de sa couleur.
            UiColor scoreTone = scoreColor(duel.score());
            float chipW = UiTheme.scaled(52f);
            float chipH = UiTheme.scaled(28f);
            float chipX = x + pad;
            float chipY = y + (h - chipH) / 2f;
            r.drawRoundedRect(chipX, chipY, chipX + chipW, chipY + chipH, UiTheme.scaled(6f),
                faded(UiColor.lerp(UiTheme.PANEL_BG, scoreTone, 0.22f)), vpWidth, vpHeight);
            float strong = UiTheme.scaled(0.62f);
            String score = scoreText(duel.score());
            r.drawText(UiFont.BOLD, score, chipX + (chipW - r.textWidth(UiFont.BOLD, score, strong)) / 2f, baseline - UiTheme.scaled(1f),
                faded(scoreTone), strong, vpWidth, vpHeight);

            // Le résultat, en capitales et dans sa couleur.
            float column = chipX + chipW + UiTheme.scaled(22f);
            r.drawText(UiFont.BOLD, resultText(duel.result()).toUpperCase(java.util.Locale.ROOT), column, baseline,
                faded(tone), normal, vpWidth, vpHeight);

            // L'adversaire, avec ce qu'il est sur une ligne plus petite au-dessus.
            column += UiTheme.scaled(124f);
            r.drawText(Lang.tr("CONTRE"), column, baseline + UiTheme.scaled(11f), faded(UiTheme.TEXT_MUTED), UiTheme.scaled(0.34f), vpWidth, vpHeight);
            String opponent = duel.opponent.isEmpty() ? Lang.tr("Adversaire inconnu") : duel.opponent;
            r.drawText(opponent, column, baseline - UiTheme.scaled(5f), faded(UiTheme.TEXT_PRIMARY), normal, vpWidth, vpHeight);

            // À droite : les opportunités converties, puis la date.
            float right = x + w - pad;
            String date = date(duel.time);
            r.drawText(date, right - r.textWidth(date, small), baseline, faded(UiTheme.TEXT_MUTED), small, vpWidth, vpHeight);
            float statRight = right - UiTheme.scaled(118f);
            String converted = (int) duel.report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) duel.report[NativeCombat.REPORT_MY_OPENED];
            r.drawText(Lang.tr("OPPORTUNITÉS"), statRight - r.textWidth(Lang.tr("OPPORTUNITÉS"), UiTheme.scaled(0.34f)), baseline + UiTheme.scaled(11f),
                faded(UiTheme.TEXT_MUTED), UiTheme.scaled(0.34f), vpWidth, vpHeight);
            r.drawText(converted, statRight - r.textWidth(converted, normal), baseline - UiTheme.scaled(5f),
                faded(UiTheme.TEXT_SECONDARY), normal, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            open.run();
        }
    }
}
