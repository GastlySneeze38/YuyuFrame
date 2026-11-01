package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
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
 * <p>Rendu du 2026-11-01, aligné sur le menu YuyuFrame : le monde reste
 * visible sous un voile, chaque bloc est une carte de VERRE au liseré fin, et
 * la couleur se fait discrète — elle porte toujours l'information (bon, moyen,
 * mauvais ; gagné, perdu) mais sur le texte, un trait ou une pastille, plus
 * jamais en grands aplats teintés. Les teintes sont adoucies pour la même
 * raison : vives, elles prenaient le dessus sur ce qu'elles qualifient.
 */
public final class PvpScoreUi {

    private PvpScoreUi() {
    }

    /** Bonne note, victoire. */
    public static final UiColor GOOD = new UiColor(104, 206, 148, 255);
    /** Note moyenne : ni bonne ni mauvaise. */
    public static final UiColor MID = new UiColor(226, 186, 104, 255);
    /** Mauvaise note, défaite. */
    public static final UiColor BAD = new UiColor(228, 112, 120, 255);
    /** Match nul, pas de note. */
    public static final UiColor NEUTRAL = new UiColor(150, 156, 176, 255);
    /**
     * L'adversaire. Ses barres ne sont pas colorées comme les nôtres : un
     * vert chez lui serait une mauvaise nouvelle pour nous, et le lire comme
     * une bonne prête à confusion. Une seule couleur, la sienne.
     */
    public static final UiColor FOE = new UiColor(128, 164, 236, 255);
    /** Le bonus bug use : à part des familles, qui ne fait que monter la note. */
    public static final UiColor BUG_USE = new UiColor(180, 150, 240, 255);

    /** « 62 + 4 bug use » : la note des familles et le bonus, à part. */
    public static String bugUseText(double baseScore, double bonus) {
        return Math.round(baseScore) + " + " + Math.round(bonus) + " " + Lang.tr("bug use");
    }

    /**
     * À partir de là, une note sur 500 est bonne ; en dessous de l'autre
     * seuil, elle est mauvaise. Les mêmes écarts au joueur moyen que 65 et 45
     * sur l'ancienne note sur 100 (2026-10-22).
     */
    public static final double GOOD_FROM = 290;
    public static final double POOR_UNDER = 235;

    /** Une note sur 500 en part d'une barre (de 0 à 100). */
    static double percent(double score) {
        return score / NativeCombat.SCORE_MAX * 100.0;
    }

    /** Des points d'action, signés : « +12 », « −4 » (la police n'a que le tiret court). */
    static String signedPoints(double points) {
        long rounded = Math.round(points);
        return rounded > 0 ? "+" + rounded : rounded < 0 ? "-" + (-rounded) : "0";
    }

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

    /** La couleur d'une note sur 500 : verte si elle est bonne, ambre si elle est moyenne, rouge sinon ; grise sans note. */
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
        // Des marges franches et une largeur bornée : de l'air autour de chaque bloc.
        float side = Math.max(UiTheme.scaled(64f), (screenWidth - UiTheme.scaled(1480f)) / 2f);
        return new float[]{ side, screenWidth - side, screenHeight - topBarHeight(), UiTheme.scaled(40f) };
    }

    /** Le bouton de retour, en haut à gauche. */
    static UiButton backButton(int screenWidth, int screenHeight, Runnable back) {
        float[] c = content(screenWidth, screenHeight);
        float w = UiTheme.scaled(140f);
        float h = UiTheme.scaled(40f);
        float y = screenHeight - topBarHeight() / 2f - h / 2f;
        return new UiButton(c[0], y, w, h, Lang.tr("Retour"), back);
    }

    /** Le bouton qui ouvre l'explication du calcul, en haut à droite : un rond de verre marqué « i ». */
    static UiWidget helpButton(int screenWidth, int screenHeight, Runnable open) {
        float[] c = content(screenWidth, screenHeight);
        float size = UiTheme.scaled(40f);
        float y = screenHeight - topBarHeight() / 2f - size / 2f;
        GlassButton button = new GlassButton(c[1] - size, y, size, null, open);
        button.tooltip = Lang.tr("Comment ça marche ?");
        return button;
    }

    /**
     * Les réglages du module (compétitif ou casual, sa touche, les mots, les
     * sons, le partage des duels), à gauche du « i » : un rond de verre à
     * engrenage. La carte du module ouvre cette page, pas ses réglages —
     * c'est leur seule entrée.
     */
    static UiWidget settingsButton(int screenWidth, int screenHeight, Runnable open) {
        float[] c = content(screenWidth, screenHeight);
        float size = UiTheme.scaled(40f);
        float y = screenHeight - topBarHeight() / 2f - size / 2f;
        GlassButton button = new GlassButton(c[1] - size - UiTheme.scaled(12f) - size, y, size, GEAR_ICON, open);
        button.tooltip = Lang.tr("Réglages : compétitif ou casual, sa touche, les mots, les sons, le partage des duels");
        return button;
    }

    /** L'engrenage, de la même famille d'icônes que celui de l'écran-titre ({@code TitleAccountBar}). */
    private static final String GEAR_ICON = com.yuyuframe.launcheragent.runtime.ui.LauncherModule.icons8("settings");

    /**
     * Un bouton rond en verre (flou du décor, teinte du thème, liseré), qui
     * se teinte de la couleur d'accent au survol — comme l'engrenage de
     * l'écran-titre. Porte une icône ({@code icon}, une adresse d'image) ou,
     * sans icône, la lettre « i ».
     */
    private static final class GlassButton extends UiWidget {
        private final String icon;
        private final Runnable action;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);

        GlassButton(float x, float y, float size, String icon, Runnable action) {
            super(x, y, size, size);
            this.icon = icon;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float t = hover.get();
            float radius = w / 2f;
            // Le liseré, puis le verre teinté de l'accent au survol (la couleur donnée est l'aplat de repli).
            r.drawRoundedRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, radius + 1f,
                UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.ACCENT, t), vw, vh);
            UiColor fallback = UiColor.lerp(UiTheme.PANEL_BG, UiTheme.ACCENT, t * 0.55f).withAlpha(1f);
            UiColor tint = UiColor.lerp(UiTheme.GLASS_TINT, UiTheme.ACCENT, t * 0.6f);
            r.drawGlassPanel(x, y, x + w, y + h, radius, tint, UiTheme.GLASS_STRENGTH_FIELD, fallback, vw, vh);
            java.awt.image.BufferedImage image = icon == null ? null
                : com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage.get(icon);
            if (image != null) {
                // Grossit un peu au survol : seul retour visuel sur un bouton sans libellé.
                float size = w * (0.5f + 0.06f * t);
                float off = (w - size) / 2f;
                r.drawIcon(icon, image, x + off, y + off, size, size, vw, vh);
                return;
            }
            // Sans icône, ou tant que l'image n'est pas arrivée : une lettre.
            float scale = UiTheme.scaled(0.7f);
            centered(r, UiFont.BOLD, icon == null ? "i" : "...", x + w / 2f, middle(y + h / 2f, scale), w, UiTheme.TEXT_PRIMARY, scale, vw, vh);
        }

        @Override
        public void onClick() {
            action.run();
        }
    }

    /**
     * Le fond d'une page : le même voile que le menu YuyuFrame — le monde du
     * jeu reste visible, c'est lui que les cartes de verre floutent —, et le
     * titre au milieu de la barre du haut, en capitales espacées.
     */
    static void drawPage(UiRenderer r, String title, int screenWidth, int screenHeight) {
        UiColor veil = r.isGlassAvailable() ? UiTheme.GLASS_SCRIM : UiTheme.OVERLAY_BG;
        r.drawRoundedRect(0f, 0f, screenWidth, screenHeight, 0f, veil, screenWidth, screenHeight);
        float bar = screenHeight - topBarHeight();
        float[] c = content(screenWidth, screenHeight);
        r.drawRoundedRect(c[0], bar, c[1], bar + Math.max(1f, UiTheme.scaled(1f)), 0f, UiTheme.GLASS_HAIRLINE, screenWidth, screenHeight);
        float scale = UiTheme.scaled(0.62f);
        centered(r, UiFont.BOLD, title, screenWidth / 2f, middle(bar + topBarHeight() / 2f, scale), screenWidth * 0.4f,
            UiTheme.TEXT_PRIMARY, scale, screenWidth, screenHeight);
    }

    // ── Animations ───────────────────────────────────────────────────────────

    /**
     * Opacité appliquée par tous les dessins de cette classe — {@link #card},
     * {@link #bar}, {@link #left}… — et par {@link #a}. Une page la pose avant
     * de dessiner un bloc qui apparaît en fondu, puis la remet à 1 : c'est ce
     * qui fait entrer un bloc entier d'un coup sans passer son opacité à
     * chaque appel. Fil de rendu seulement.
     */
    static float alpha = 1f;

    /** Une couleur à l'opacité courante. */
    static UiColor a(UiColor color) {
        return alpha >= 1f ? color : color.multiplyAlpha(Math.max(0f, alpha));
    }

    /**
     * L'avancement d'une animation partie à {@code startNanos}, après
     * {@code delay} secondes et sur {@code duration} secondes, de 0 à 1, en
     * ralentissant sur la fin.
     */
    static float ease(long startNanos, float delay, float duration) {
        float t = ((System.nanoTime() - startNanos) / 1e9f - delay) / duration;
        return UiEasing.CUBIC_OUT.apply(Math.max(0f, Math.min(1f, t)));
    }

    /** Comme {@link #ease}, avec un léger dépassement avant de se poser : pour ce qui « saute » à sa place. */
    static float pop(long startNanos, float delay, float duration) {
        float t = ((System.nanoTime() - startNanos) / 1e9f - delay) / duration;
        return UiEasing.BACK_OUT.apply(Math.max(0f, Math.min(1f, t)));
    }

    /** Avancement brut (sans courbe) d'une animation, de 0 à 1. */
    static float linear(long startNanos, float delay, float duration) {
        float t = ((System.nanoTime() - startNanos) / 1e9f - delay) / duration;
        return Math.max(0f, Math.min(1f, t));
    }

    /** Courbe à léger dépassement sur un avancement déjà calculé : pour ce qui « saute » à sa taille. */
    static float backOut(float t) {
        float c1 = 1.3f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** Un nombre qui monte de 0 à sa valeur pendant que {@code progress} va de 0 à 1. */
    static long count(double value, float progress) {
        return Math.round(value * progress);
    }

    // ── Surfaces ─────────────────────────────────────────────────────────────

    /** Un rectangle arrondi à l'opacité courante. */
    static void rect(UiRenderer r, float x1, float y1, float x2, float y2, float radius, UiColor color, int vw, int vh) {
        r.drawRoundedRect(x1, y1, x2, y2, radius, a(color), vw, vh);
    }

    /** Un texte à l'opacité courante. */
    static void text(UiRenderer r, UiFont font, String text, float x, float y, UiColor color, float scale, int vw, int vh) {
        r.drawText(font, text, x, y, a(color), scale, vw, vh);
    }

    /** Rayon des cartes de ces pages. */
    static float radius() {
        return UiTheme.scaled(14f);
    }

    /**
     * Une carte de verre, comme celles du menu YuyuFrame : le décor flouté
     * teinté, un liseré fin tout autour. L'opacité courante ({@link #alpha})
     * passe par la couleur de repli, qui pilote aussi celle du verre.
     */
    static void card(UiRenderer r, float x1, float y1, float x2, float y2, int vw, int vh) {
        float radius = radius();
        UiColor border = r.isGlassAvailable() ? a(UiTheme.GLASS_BORDER) : null;
        r.drawGlassPanel(x1, y1, x2, y2, radius, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_CARD, a(UiTheme.CARD_BG),
            border, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
    }

    /**
     * Une carte qui porte une couleur, discrètement : la carte de verre, et un
     * trait de la couleur en haut, rentré des coins. {@code strength} règle
     * l'opacité du trait (de 0 à 1 ; 0,3 donne un trait franc). La couleur
     * n'envahit plus le fond : elle se lit sur le trait et sur le texte.
     */
    static void tintedCard(UiRenderer r, float x1, float y1, float x2, float y2, UiColor tone, float strength, int vw, int vh) {
        card(r, x1, y1, x2, y2, vw, vh);
        float inset = radius();
        float line = Math.max(1.5f, UiTheme.scaled(2f));
        float opacity = Math.max(0.35f, Math.min(1f, strength * 2.6f));
        r.drawRoundedRect(x1 + inset, y2 - line, x2 - inset, y2, line / 2f, a(tone.withAlpha(opacity)), vw, vh);
    }

    /** Un intitulé de section : petites capitales grises, posées à gauche. */
    static void label(UiRenderer r, String text, float x, float y, float maxWidth, int vw, int vh) {
        left(r, UiFont.BOLD, text.toUpperCase(java.util.Locale.ROOT), x, y, maxWidth, UiTheme.TEXT_MUTED, UiTheme.scaled(0.38f), vw, vh);
    }

    private static final UiColor TRACK = new UiColor(255, 255, 255, 16);

    /** Une barre de 0 à 100 dans sa couleur, sur un rail sombre. */
    static void bar(UiRenderer r, float x1, float y, float x2, float height, double value, UiColor tone, int vw, int vh) {
        float radius = height / 2f;
        tone = a(tone);
        r.drawRoundedRect(x1, y, x2, y + height, radius, a(TRACK), vw, vh);
        float filled = x1 + (x2 - x1) * (float) (Math.max(0, Math.min(100, value)) / 100.0);
        if (filled > x1 + height) {
            r.drawRoundedRect(x1, y, filled, y + height, radius, tone.multiplyAlpha(0.85f), vw, vh);
        }
    }

    /** Une barre qui part de la droite vers la gauche : le côté « vous » d'un face-à-face. */
    static void barLeftward(UiRenderer r, float x1, float y, float x2, float height, double value, UiColor tone, int vw, int vh) {
        float radius = height / 2f;
        tone = a(tone);
        r.drawRoundedRect(x1, y, x2, y + height, radius, a(TRACK), vw, vh);
        float filled = x2 - (x2 - x1) * (float) (Math.max(0, Math.min(100, value)) / 100.0);
        if (filled < x2 - height) {
            r.drawRoundedRect(filled, y, x2, y + height, radius, tone.multiplyAlpha(0.85f), vw, vh);
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
        r.drawText(font, text, x, y, a(color), fit(r, font, text, scale, maxWidth), vw, vh);
    }

    /** Un texte centré sur {@code center}, rétréci s'il dépasse : une traduction plus longue ne sort pas de sa case. */
    static void centered(UiRenderer r, UiFont font, String text, float center, float y, float maxWidth, UiColor color, float scale, int vw, int vh) {
        float s = fit(r, font, text, scale, maxWidth);
        r.drawText(font, text, center - r.textWidth(font, text, s) / 2f, y, a(color), s, vw, vh);
    }

    /** Un texte calé à droite sur {@code right}, rétréci s'il dépasse. */
    static void right(UiRenderer r, UiFont font, String text, float right, float y, float maxWidth, UiColor color, float scale, int vw, int vh) {
        float s = fit(r, font, text, scale, maxWidth);
        r.drawText(font, text, right - r.textWidth(font, text, s), y, a(color), s, vw, vh);
    }

    // ── Le choix du mode ─────────────────────────────────────────────────────

    /**
     * Les modes de jeu notés, dans l'ordre de la liste déroulante : la masse,
     * et l'épée seule depuis le 2026-10-22 (un kit où chacun n'a que son
     * épée). Un mode s'ajoute ici le jour où le noyau sait le noter.
     */
    static final String[] MODES = { "Masse", "Épée" };
    /** Le code de chaque mode dans le résumé du noyau ({@code NativeCombat.REPORT_MODE}), dans l'ordre de {@link #MODES}. */
    static final int[] MODE_CODES = { 1, 2 };

    /** Pourquoi un duel n'a pas de note, en deux lignes ({@code NativeCombat.REPORT_REASON}). */
    public static String[] reasonLines(double[] report) {
        double reason = report[NativeCombat.REPORT_REASON];
        if (reason == 1) return new String[]{ "Duel trop court", "pour être noté" };
        if (reason == 3) return new String[]{ "Ce mode de jeu", "n'est pas encore noté" };
        if (PvpScoreModule.isSword(report)) return new String[]{ "Trop peu de coups", "pour être noté" };
        return new String[]{ "Trop peu d'opportunités", "pour être noté" };
    }

    /**
     * La liste déroulante du mode affiché. Le bouton est un widget de l'écran ;
     * la liste ouverte est dessinée par l'écran après tout le reste
     * ({@link #drawPanel}), pour passer par-dessus l'historique. Elle s'ouvre en
     * se dépliant, et dit sous les modes que d'autres arrivent.
     */
    static final class ModeDropdown extends UiWidget {
        private final java.util.function.IntConsumer onChange;
        private int selected;
        private boolean expanded;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        private final UiAnimatedFloat unfold = new UiAnimatedFloat(0f, 14f);
        /** Le clic qui choisit un mode referme la liste : on s'en souvient un instant (voir {@code UiDropdown}). */
        private long clickTakenAtNanos;

        ModeDropdown(float x, float y, float w, float h, int selected, java.util.function.IntConsumer onChange) {
            super(x, y, w, h);
            this.selected = Math.max(0, Math.min(MODES.length - 1, selected));
            this.onChange = onChange;
        }

        private float gap() { return UiTheme.scaled(6f); }
        private float noteHeight() { return UiTheme.scaled(30f); }
        private float panelTop() { return y - gap(); }
        private float panelBottom() { return panelTop() - MODES.length * h - noteHeight(); }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) || expanded ? 1f : 0f);
            float t = hover.get();
            float radius = UiTheme.scaled(9f);
            UiColor border = UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, t);
            r.drawGlassPanel(x, y, x + w, y + h, radius, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD - 0.08f * t,
                UiTheme.CARD_BG, r.isGlassAvailable() ? border : null, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            float pad = UiTheme.scaled(16f);
            float small = UiTheme.scaled(0.36f);
            float value = UiTheme.scaled(0.5f);
            float mid = y + h / 2f;
            r.drawText(UiFont.BOLD, Lang.tr("MODE"), x + pad, middle(mid, small), UiTheme.TEXT_MUTED, small, vw, vh);
            float labelW = r.textWidth(UiFont.BOLD, Lang.tr("MODE"), small) + UiTheme.scaled(12f);
            left(r, UiFont.BOLD, Lang.tr(MODES[selected]), x + pad + labelW, middle(mid, value), w - pad * 3f - labelW,
                UiTheme.TEXT_PRIMARY, value, vw, vh);
            // Le chevron se retourne en s'ouvrant : il descend d'un cran en se fondant, et l'autre arrive.
            float open = unfold.get();
            float chevron = UiTheme.scaled(0.5f);
            float cx = x + w - pad - r.textWidth(UiFont.BOLD, "v", chevron);
            r.drawText(UiFont.BOLD, "v", cx, middle(mid, chevron) - open * UiTheme.scaled(4f),
                UiTheme.TEXT_SECONDARY.multiplyAlpha(1f - open), chevron, vw, vh);
            r.drawText(UiFont.BOLD, "^", cx, middle(mid, chevron) + (1f - open) * UiTheme.scaled(4f) - UiTheme.scaled(3f),
                UiTheme.ACCENT.multiplyAlpha(open), chevron, vw, vh);
        }

        /** La liste ouverte : elle se déplie vers le bas, ses lignes apparaissent à mesure. */
        void drawPanel(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            unfold.setTarget(expanded ? 1f : 0f);
            float open = unfold.get();
            if (open < 0.02f) return;
            float top = panelTop();
            float bottom = panelBottom();
            float radius = UiTheme.scaled(9f);
            // Le panneau sort du bouton : il grandit depuis son bord haut avec
            // un petit rebond, ses lignes arrivent ensuite une à une.
            float c1 = 1.4f, u = open - 1f;
            float grow = 1f + (c1 + 1f) * u * u * u + c1 * u * u;
            r.pushLayer(Math.min(1f, open * 1.6f), 0.82f + 0.18f * grow, x + w / 2f, top, 0f, (1f - open) * UiTheme.scaled(10f));
            try {
                r.drawShadow(x, bottom, x + w, top, radius, 16f, 0f, new UiColor(0, 0, 0, 130), vw, vh);
                r.drawGlassPanel(x, bottom, x + w, top, radius, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, UiTheme.PANEL_BG_ALT,
                    r.isGlassAvailable() ? UiTheme.GLASS_BORDER_HOVER : null, Math.max(1f, UiTheme.scaled(1f)), vw, vh);

                float pad = UiTheme.scaled(14f);
                float value = UiTheme.scaled(0.5f);
                int lines = MODES.length + 1;
                for (int i = 0; i < MODES.length; i++) {
                    float rowTop = top - i * h;
                    float rowBottom = rowTop - h;
                    float rowT = Math.max(0f, Math.min(1f, (open - 0.25f * i / lines) / 0.75f));
                    boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
                    r.pushLayer(rowT, 1f, x, rowBottom, -(1f - rowT) * UiTheme.scaled(14f), 0f);
                    try {
                        if (i == selected || hovered) {
                            r.drawRoundedRect(x + UiTheme.scaled(4f), rowBottom + UiTheme.scaled(3f), x + w - UiTheme.scaled(4f), rowTop - UiTheme.scaled(3f),
                                UiTheme.scaled(7f), i == selected ? UiTheme.ACCENT.withAlpha(0.3f) : new UiColor(255, 255, 255, 16), vw, vh);
                        }
                        r.drawText(i == selected ? UiFont.BOLD : UiFont.REGULAR, Lang.tr(MODES[i]),
                            x + pad + (hovered ? UiTheme.scaled(3f) : 0f), middle((rowTop + rowBottom) / 2f, value),
                            UiTheme.TEXT_PRIMARY, value, vw, vh);
                    } finally {
                        r.popLayer();
                    }
                }
                float noteTop = top - MODES.length * h;
                float noteT = Math.max(0f, Math.min(1f, (open - 0.25f * MODES.length / lines) / 0.75f));
                float small = UiTheme.scaled(0.36f);
                r.pushLayer(noteT, 1f, x, noteTop, -(1f - noteT) * UiTheme.scaled(14f), 0f);
                try {
                    left(r, UiFont.REGULAR, Lang.tr("D'autres modes arrivent bientôt"), x + pad, middle(noteTop - noteHeight() / 2f, small),
                        w - pad * 2f, UiTheme.TEXT_MUTED, small, vw, vh);
                } finally {
                    r.popLayer();
                }
            } finally {
                r.popLayer();
            }
        }

        @Override
        public void onClick() {
            expanded = !expanded;
        }

        /** La liste est ouverte, ou vient de prendre un clic : ce qu'elle recouvre ne doit pas le recevoir aussi. */
        boolean busy(double mouseX, double mouseY) {
            return expanded || overlayContains(mouseX, mouseY);
        }

        private boolean overPanel(double mx, double my) {
            return mx >= x && mx <= x + w && my >= panelBottom() && my <= panelTop();
        }

        @Override
        public boolean overlayContains(double mouseX, double mouseY) {
            boolean justTaken = clickTakenAtNanos != 0L && System.nanoTime() - clickTakenAtNanos < 200_000_000L;
            return (expanded || justTaken) && overPanel(mouseX, mouseY);
        }

        @Override
        public void pollContinuous(UiInputPoller input) {
            if (!expanded || !input.leftClicked) return;
            for (int i = 0; i < MODES.length; i++) {
                float rowTop = panelTop() - i * h;
                if (input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= rowTop - h && input.mouseY <= rowTop) {
                    expanded = false;
                    clickTakenAtNanos = System.nanoTime();
                    if (i != selected) {
                        selected = i;
                        if (onChange != null) onChange.accept(i);
                    }
                    return;
                }
            }
            // Ailleurs que sur le bouton (qui se gère lui-même) : la liste se referme.
            if (overPanel(input.mouseX, input.mouseY)) {
                clickTakenAtNanos = System.nanoTime();
                return;
            }
            if (!contains(input.mouseX, input.mouseY)) expanded = false;
        }
    }

    // ── Casual / compétitif ──────────────────────────────────────────────────

    /**
     * Les deux façons de jouer le mode choisi dans la liste déroulante, dans
     * l'ordre du sélecteur. Seul le casual est noté aujourd'hui.
     */
    static final String[] PLAY_TYPES = { "CASUAL", "COMPÉTITIF" };

    /**
     * Deux boutons collés, l'un allumé : casual ou compétitif, pour le mode
     * de la liste déroulante d'à côté. La pastille de l'option choisie glisse
     * d'un côté à l'autre.
     */
    static final class PlayTypeToggle extends UiWidget {
        private final java.util.function.IntConsumer onChange;
        private int selected;
        private final UiAnimatedFloat slide;
        private final UiAnimatedFloat hoverLeft = new UiAnimatedFloat(0f, 16f);
        private final UiAnimatedFloat hoverRight = new UiAnimatedFloat(0f, 16f);
        private double lastMouseX;

        PlayTypeToggle(float x, float y, float w, float h, int selected, java.util.function.IntConsumer onChange) {
            super(x, y, w, h);
            this.selected = Math.max(0, Math.min(PLAY_TYPES.length - 1, selected));
            this.slide = new UiAnimatedFloat(this.selected, 14f);
            this.onChange = onChange;
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            lastMouseX = mouseX;
            float half = w / 2f;
            boolean inside = contains(mouseX, mouseY);
            hoverLeft.setTarget(inside && mouseX < x + half ? 1f : 0f);
            hoverRight.setTarget(inside && mouseX >= x + half ? 1f : 0f);
            slide.setTarget(selected);
            float radius = UiTheme.scaled(9f);
            float inset = UiTheme.scaled(4f);
            r.drawGlassPanel(x, y, x + w, y + h, radius, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, a(UiTheme.CARD_BG),
                r.isGlassAvailable() ? a(UiTheme.GLASS_BORDER) : null, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            float pillLeft = x + inset + slide.get() * half;
            r.drawRoundedRect(pillLeft, y + inset, pillLeft + half - inset * 2f, y + h - inset, radius - inset / 2f,
                a(UiTheme.ACCENT.withAlpha(0.32f)), vw, vh);
            float scale = UiTheme.scaled(0.46f);
            float mid = y + h / 2f;
            for (int i = 0; i < PLAY_TYPES.length; i++) {
                float hovered = i == 0 ? hoverLeft.get() : hoverRight.get();
                UiColor color = i == selected ? UiTheme.TEXT_PRIMARY
                    : UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hovered);
                centered(r, UiFont.BOLD, Lang.tr(PLAY_TYPES[i]), x + half * i + half / 2f, middle(mid, scale),
                    half - inset * 4f, color, scale, vw, vh);
            }
        }

        @Override
        public void onClick() {
            int clicked = lastMouseX < x + w / 2f ? 0 : 1;
            if (clicked == selected) return;
            selected = clicked;
            if (onChange != null) onChange.accept(clicked);
        }
    }

    // ── Ce que le joueur joue ────────────────────────────────────────────────

    /**
     * Le choix compétitif / casual sur la carte du rang — ce que le joueur
     * JOUE, pas ce qu'il regarde ({@link PlayTypeToggle}). En compétitif : une
     * pastille « TU JOUES EN COMPÉTITIF » dans la couleur du rang, interrupteur
     * allumé. En casual : un grand bouton « REPASSER EN COMPÉTITIF » qui
     * respire. Le réglage est celui du module
     * ({@code PvpScoreModule#competitive}).
     *
     * <p>La carte du rang est dessinée APRÈS les widgets de la page : ce
     * widget ne dessine donc rien dans {@link #draw}, la page l'appelle par
     * {@link #paint} une fois la carte posée. Il garde la zone cliquable.
     */
    static final class PlayingSwitch extends UiWidget {
        private final Runnable onChange;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        private final long createdAt = System.nanoTime();

        PlayingSwitch(float x, float y, float w, float h, Runnable onChange) {
            super(x, y, w, h);
            this.onChange = onChange;
        }

        static boolean playing() {
            com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule module =
                com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule.instance();
            return module == null || module.competitive();
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            // Dessiné par la page, par-dessus la carte : voir paint.
        }

        /** {@code tone} : la couleur du rang du mode affiché. */
        void paint(UiRenderer r, double mouseX, double mouseY, UiColor tone, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float t = hover.get();
            float radius = h / 2f;
            if (playing()) {
                r.drawGlassPanel(x, y, x + w, y + h, radius, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, a(UiTheme.CARD_BG),
                    r.isGlassAvailable() ? a(UiColor.lerp(UiTheme.GLASS_BORDER, tone.withAlpha(0.7f), 0.4f + 0.6f * t)) : null,
                    Math.max(1f, UiTheme.scaled(1f)), vw, vh);
                float trackH = h * 0.5f;
                float trackW = trackH * 1.8f;
                float pad = (h - trackH) / 2f;
                float tx = x + w - pad - trackW, ty = y + pad;
                r.drawRoundedRect(tx, ty, tx + trackW, ty + trackH, trackH / 2f, a(tone), vw, vh);
                float knob = trackH - 4f;
                float kx = tx + trackW - knob - 2f;
                r.drawRoundedRect(kx, ty + 2f, kx + knob, ty + 2f + knob, knob / 2f, a(UiTheme.TEXT_PRIMARY), vw, vh);
                float scale = UiTheme.scaled(0.4f);
                left(r, UiFont.BOLD, Lang.tr("TU JOUES EN COMPÉTITIF"), x + UiTheme.scaled(14f), middle(y + h / 2f, scale),
                    tx - x - UiTheme.scaled(22f), UiTheme.TEXT_PRIMARY, scale, vw, vh);
            } else {
                // Le bouton qui ramène au compétitif : il respire pour appeler le clic.
                // Il respire à peine : il appelle le clic sans crier.
                float breath = 0.5f + 0.5f * (float) Math.sin((System.nanoTime() - createdAt) / 1e9 * 2.0);
                r.drawRoundedRect(x, y, x + w, y + h, UiTheme.scaled(12f),
                    a(UiColor.lerp(UiTheme.ACCENT.withAlpha(0.72f), UiTheme.ACCENT, Math.max(t, 0.25f * breath))), vw, vh);
                float scale = UiTheme.scaled(0.52f);
                centered(r, UiFont.BOLD, Lang.tr("REPASSER EN COMPÉTITIF"), x + w / 2f, middle(y + h / 2f, scale), w - UiTheme.scaled(24f),
                    UiTheme.TEXT_PRIMARY, scale, vw, vh);
            }
        }

        @Override
        public void onClick() {
            com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule module =
                com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule.instance();
            if (module == null) return;
            module.setCompetitive(!module.competitive());
            if (onChange != null) onChange.run();
        }
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
