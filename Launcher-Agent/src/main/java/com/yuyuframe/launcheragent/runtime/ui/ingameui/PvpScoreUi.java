package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Ce que les pages du Score PvP ({@link UiPvpScoreScreen}, {@link UiPvpDuelScreen}),
 * leur fenêtre d'explication ({@link PvpInfoModal}) et le panneau de fin de
 * duel du HUD ont en commun : les couleurs, le cadre d'une page plein écran,
 * et quelques façons de poser du texte.
 *
 * <p>Les couleurs sont plus vives que celles du thème : sur ces pages, la
 * couleur EST l'information (bon, moyen, mauvais ; gagné, perdu), elle doit se
 * lire d'un coup d'œil. Les teintes de fond ne sont plus un mélange avec le
 * gris des cartes — qui donnait du brun et du vert-de-gris — mais la couleur
 * elle-même, transparente, en dégradé.
 */
public final class PvpScoreUi {

    private PvpScoreUi() {
    }

    /** Bonne note, victoire. */
    public static final UiColor GOOD = new UiColor(70, 226, 132, 255);
    /** Note moyenne : ni bonne ni mauvaise. */
    public static final UiColor MID = new UiColor(255, 192, 62, 255);
    /** Mauvaise note, défaite. */
    public static final UiColor BAD = new UiColor(255, 88, 96, 255);
    /** Match nul, pas de note. */
    public static final UiColor NEUTRAL = new UiColor(158, 166, 188, 255);
    /**
     * L'adversaire. Ses barres ne sont pas colorées comme les nôtres : un
     * vert chez lui serait une mauvaise nouvelle pour nous, et le lire comme
     * une bonne prête à confusion. Une seule couleur, la sienne.
     */
    public static final UiColor FOE = new UiColor(104, 158, 255, 255);

    /** À partir de là, une note est bonne ; en dessous de l'autre seuil, elle est mauvaise. */
    public static final double GOOD_FROM = 65;
    public static final double POOR_UNDER = 45;

    /** Ce qui tient lieu de note quand il n'y en a pas (la police n'a pas de tiret long). */
    public static final String NO_SCORE = "-";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    /** La couleur d'un résultat : vert, rouge, ou neutre pour un nul. */
    public static UiColor resultColor(int result) {
        return result == 1 ? GOOD : result == 2 ? BAD : NEUTRAL;
    }

    public static String resultText(int result) {
        return result >= 0 && result < PvpScoreModule.RESULTS.length ? Lang.tr(PvpScoreModule.RESULTS[result]) : "";
    }

    /** La couleur d'une note sur 100 : verte si elle est bonne, ambre si elle est moyenne, rouge sinon ; grise sans note. */
    public static UiColor scoreColor(double score) {
        if (score < 0) return NEUTRAL;
        return score >= GOOD_FROM ? GOOD : score >= POOR_UNDER ? MID : BAD;
    }

    public static String scoreText(double score) {
        return score >= 0 ? String.valueOf(Math.round(score)) : NO_SCORE;
    }

    /** La date d'un duel, ou rien si elle n'est pas connue. */
    static String date(long millis) {
        return millis > 0 ? DATE.format(Instant.ofEpochMilli(millis)) : "";
    }

    static String duration(double seconds) {
        long total = Math.round(seconds);
        return total >= 60 ? (total / 60) + " min " + (total % 60) + " s" : total + " s";
    }

    // ── La page ──────────────────────────────────────────────────────────────

    /** Hauteur de la barre du haut : retour, titre, explication. */
    static float topBarHeight() {
        return UiTheme.scaled(72f);
    }

    /**
     * La zone de contenu d'une page plein écran, {@code {gauche, droite, haut, bas}} :
     * tout l'écran moins des marges, et pas plus large que ce qui se lit sur
     * un très grand écran.
     */
    static float[] content(int screenWidth, int screenHeight) {
        float side = Math.max(UiTheme.scaled(40f), (screenWidth - UiTheme.scaled(1560f)) / 2f);
        return new float[]{ side, screenWidth - side, screenHeight - topBarHeight(), UiTheme.scaled(28f) };
    }

    /** Le bouton de retour, en haut à gauche. */
    static UiButton backButton(int screenWidth, int screenHeight, Runnable back) {
        float[] c = content(screenWidth, screenHeight);
        float w = UiTheme.scaled(140f);
        float h = UiTheme.scaled(40f);
        float y = screenHeight - topBarHeight() / 2f - h / 2f;
        return new UiButton(c[0], y, w, h, Lang.tr("Retour"), back);
    }

    /** Le bouton qui ouvre l'explication du calcul, en haut à droite : une simple icône d'information. */
    static UiWidget helpButton(int screenWidth, int screenHeight, Runnable open) {
        float[] c = content(screenWidth, screenHeight);
        float size = UiTheme.scaled(40f);
        float y = screenHeight - topBarHeight() / 2f - size / 2f;
        InfoButton button = new InfoButton(c[1] - size, y, size, open);
        button.tooltip = Lang.tr("Comment ça marche ?");
        return button;
    }

    /** Un bouton rond marqué « i ». */
    private static final class InfoButton extends UiWidget {
        private final Runnable action;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);

        InfoButton(float x, float y, float size, Runnable action) {
            super(x, y, size, size);
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float t = hover.get();
            float radius = w / 2f;
            r.drawRoundedRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, radius + 1f,
                UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.ACCENT, t), vw, vh);
            r.drawRoundedRect(x, y, x + w, y + h, radius,
                UiColor.lerp(UiTheme.CARD_BG, UiTheme.ACCENT.withAlpha(0.45f), t), vw, vh);
            float scale = UiTheme.scaled(0.7f);
            centered(r, UiFont.BOLD, "i", x + w / 2f, middle(y + h / 2f, scale), w, UiTheme.TEXT_PRIMARY, scale, vw, vh);
        }

        @Override
        public void onClick() {
            action.run();
        }
    }

    /** Le fond d'une page : tout l'écran en verre, et le titre au milieu de la barre du haut. */
    static void drawPage(UiRenderer r, String title, int screenWidth, int screenHeight) {
        r.drawGlassPanel(0f, 0f, screenWidth, screenHeight, 0f,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG, screenWidth, screenHeight);
        float bar = screenHeight - topBarHeight();
        r.drawRoundedRect(0f, bar, screenWidth, bar + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_BORDER, screenWidth, screenHeight);
        float scale = UiTheme.scaled(0.7f);
        centered(r, UiFont.BOLD, title, screenWidth / 2f, middle(bar + topBarHeight() / 2f, scale), screenWidth * 0.4f,
            UiTheme.TEXT_PRIMARY, scale, screenWidth, screenHeight);
    }

    // ── Surfaces ─────────────────────────────────────────────────────────────

    /** Une carte grise, comme partout dans le client. */
    static void card(UiRenderer r, float x1, float y1, float x2, float y2, int vw, int vh) {
        r.drawRoundedRect(x1, y1, x2, y2, UiTheme.scaled(10f), UiTheme.CARD_BG, vw, vh);
    }

    /**
     * Une carte teintée par une couleur : la carte grise, puis la couleur en
     * transparence, plus franche à gauche qu'à droite. {@code strength} est
     * l'opacité de la couleur côté gauche.
     */
    static void tintedCard(UiRenderer r, float x1, float y1, float x2, float y2, UiColor tone, float strength, int vw, int vh) {
        float radius = UiTheme.scaled(10f);
        r.drawRoundedRect(x1, y1, x2, y2, radius, UiTheme.CARD_BG, vw, vh);
        UiColor strong = tone.withAlpha(strength);
        UiColor faint = tone.withAlpha(strength * 0.25f);
        r.drawGradientRect2D(x1, y1, x2, y2, radius, strong, faint, strong, faint, vw, vh);
    }

    /** Une barre de 0 à 100 dans sa couleur, sur un rail sombre. */
    static void bar(UiRenderer r, float x1, float y, float x2, float height, double value, UiColor tone, int vw, int vh) {
        float radius = height / 2f;
        r.drawRoundedRect(x1, y, x2, y + height, radius, new UiColor(0, 0, 0, 90), vw, vh);
        float filled = x1 + (x2 - x1) * (float) (Math.max(0, Math.min(100, value)) / 100.0);
        if (filled > x1 + height) {
            r.drawGradientRect2D(x1, y, filled, y + height, radius, tone.multiplyAlpha(0.7f), tone, tone.multiplyAlpha(0.7f), tone, vw, vh);
        }
    }

    /** Une barre qui part de la droite vers la gauche : le côté « vous » d'un face-à-face. */
    static void barLeftward(UiRenderer r, float x1, float y, float x2, float height, double value, UiColor tone, int vw, int vh) {
        float radius = height / 2f;
        r.drawRoundedRect(x1, y, x2, y + height, radius, new UiColor(0, 0, 0, 90), vw, vh);
        float filled = x2 - (x2 - x1) * (float) (Math.max(0, Math.min(100, value)) / 100.0);
        if (filled < x2 - height) {
            r.drawGradientRect2D(filled, y, x2, y + height, radius, tone, tone.multiplyAlpha(0.7f), tone, tone.multiplyAlpha(0.7f), vw, vh);
        }
    }

    // ── Texte ────────────────────────────────────────────────────────────────

    /** Ce qu'un texte occupe au-dessus de sa ligne de base, par unité d'échelle. */
    private static final float HALF_CAP = 11f;

    /** La ligne de base qui centre un texte de cette échelle sur {@code centerY}. */
    static float middle(float centerY, float scale) {
        return centerY - HALF_CAP * scale;
    }

    /** L'échelle à laquelle un texte tient dans {@code maxWidth} — la sienne s'il y tient déjà. */
    static float fit(UiRenderer r, UiFont font, String text, float scale, float maxWidth) {
        float width = r.textWidth(font, text, scale);
        return width > maxWidth && width > 0f ? scale * maxWidth / width : scale;
    }

    /** Un texte posé à gauche, rétréci s'il dépasse. */
    static void left(UiRenderer r, UiFont font, String text, float x, float y, float maxWidth, UiColor color, float scale, int vw, int vh) {
        r.drawText(font, text, x, y, color, fit(r, font, text, scale, maxWidth), vw, vh);
    }

    /** Un texte centré sur {@code center}, rétréci s'il dépasse : une traduction plus longue ne sort pas de sa case. */
    static void centered(UiRenderer r, UiFont font, String text, float center, float y, float maxWidth, UiColor color, float scale, int vw, int vh) {
        float s = fit(r, font, text, scale, maxWidth);
        r.drawText(font, text, center - r.textWidth(font, text, s) / 2f, y, color, s, vw, vh);
    }

    /** Un texte calé à droite sur {@code right}, rétréci s'il dépasse. */
    static void right(UiRenderer r, UiFont font, String text, float right, float y, float maxWidth, UiColor color, float scale, int vw, int vh) {
        float s = fit(r, font, text, scale, maxWidth);
        r.drawText(font, text, right - r.textWidth(font, text, s), y, color, s, vw, vh);
    }

    /** Un paragraphe coupé en lignes de {@code maxWidth} au plus, aux espaces. */
    static List<String> wrap(UiRenderer r, UiFont font, String text, float scale, float maxWidth) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && r.textWidth(font, candidate, scale) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
