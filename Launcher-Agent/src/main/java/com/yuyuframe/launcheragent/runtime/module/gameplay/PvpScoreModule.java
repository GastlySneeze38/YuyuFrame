package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRegistry;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.combat.CombatTracker;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpScoreUi;

/**
 * Score PvP (docs/scoring/) : allumé, le module relève les duels à la masse,
 * le noyau natif les reconnaît, les note et écrit journal et résumé dans
 * {@code yuyuframe/matches/} du dossier de l'instance. À la fin d'un duel, un
 * panneau montre la note quelques secondes.
 *
 * <p>Le module ne contient aucune logique de jeu. Java relève
 * ({@link CombatTracker}) et affiche ; Rust reconnaît les duels et calcule
 * ({@code content-core/src/combat/}). Le résumé arrive ici en nombres, déjà
 * calculés ({@link NativeCombat#report}).
 *
 * <p>Le panneau de fin de duel est un élément du HUD comme les autres : il se
 * place et se redimensionne dans l'éditeur de HUD, où il montre un duel
 * d'exemple tant qu'aucun vrai duel n'a été noté. En jeu, il n'apparaît que
 * les quelques secondes qui suivent un duel.
 *
 * <p>La carte du module ouvre l'historique ({@code UiPvpScoreScreen}) : la
 * moyenne des notes, puis les duels, chacun menant à son détail. Le module
 * n'a aucun réglage — les duels sont reconnus sans rien demander au joueur.
 *
 * <p>Éteint par défaut : tant que le score n'est pas calibré, personne ne
 * doit se retrouver avec des journaux écrits sans l'avoir demandé.
 */
public final class PvpScoreModule extends LauncherModule implements HudElementOwner {

    /** Les versions dont les mixins de combat existent ({@code apimixin/<version>/combat}). */
    public static final java.util.Set<String> VERSIONS = java.util.Set.of("26.1.2", "26.3");

    /** Libellés des six familles, dans l'ordre de {@link NativeCombat#REPORT_FAMILIES}. */
    public static final String[] FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle vertical", "Ressources", "Niveau adverse",
    };
    /** Libellés des résultats, par code ({@link NativeCombat#REPORT_RESULT}). */
    public static final String[] RESULTS = { "", "Victoire", "Défaite", "Match nul", "" };

    private final Summary summary = new Summary();
    private final HudElement element = new HudElement("pvp-score", "Score PvP", HudAnchor.TOP_CENTER, 0f, 36f,
        (HudElement.CustomRenderer) summary);

    private final double[] incoming = new double[NativeCombat.REPORT_LEN];
    /** Le numéro du dernier résumé reçu — celui d'avant le lancement n'est pas montré. */
    private int lastReport = NativeCombat.report(new double[NativeCombat.REPORT_LEN]);

    public PvpScoreModule() {
        super("pvp-score", "Score PvP",
            "Note vos duels à la masse et affiche le résultat à la fin de chacun. En cours de développement : la note n'est pas encore calibrée.",
            "Note de vos duels à la masse", false,
            // DÉCLARÉS, sinon les mixins correspondants ne sont pas tissés du
            // tout (voir MixinHookPointRegistry).
            HookPoint.CLIENT_TICK, HookPoint.SERVER_CONNECT, HookPoint.CLIENT_LEVEL_LOAD,
            HookPoint.ENTITY_ATTACK, HookPoint.DAMAGE_EVENT, HookPoint.ENTITY_EVENT, HookPoint.ITEM_USE,
            HookPoint.SOUND_PLAYED, HookPoint.ITEM_COOLDOWN);
        iconUrl = icons8("sword");
        CombatTracker.install();
        CombatTracker.setActive(isEnabled());
    }

    @Override
    public HudElement hudElement() {
        return element;
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
        if (enabled) HudRegistry.register(element);
        else HudRegistry.unregister(element);
    }

    /** À chaque image : un nouveau résumé est-il arrivé du noyau ? */
    @Override
    public void onTick() {
        int number = NativeCombat.report(incoming);
        if (number != lastReport) {
            lastReport = number;
            summary.show(incoming);
        }
    }

    /**
     * Le panneau de fin de duel. Trois bandes, de haut en bas : le résultat ;
     * la note dans son cadre, à côté des six familles ; les chiffres du duel.
     * Mêmes couleurs que les pages de l'historique — la note et chaque barre
     * dans la couleur de leur valeur ({@code PvpScoreUi.scoreColor}), le
     * niveau adverse en gris parce qu'il parle de l'autre.
     *
     * <p>Le fond est la carte de verre du HUD ({@link #glassCard}). Toutes les
     * mesures sont à l'échelle 1 et multipliées par l'échelle de l'élément.
     * Repère du moteur : l'origine est en bas à gauche, y monte.
     */
    private static final class Summary implements HudElement.CustomRenderer {

        /** Durée d'affichage après un duel. */
        private static final long SHOWN_NANOS = 12_000_000_000L;

        private static final float WIDTH = 320f;
        private static final float HEADER_H = 30f;
        private static final float GAP = 8f;
        private static final float ROW = 15f;
        private static final float BODY_H = NativeCombat.FAMILY_COUNT * ROW + 10f;
        private static final float FIGURES_H = 36f;
        private static final float HEIGHT = HEADER_H + GAP + BODY_H + GAP + FIGURES_H;
        private static final float SCORE_W = 108f;
        private static final float RADIUS = 6f;

        /** Ce qu'un texte occupe au-dessus de sa ligne de base, par unité d'échelle : sert à le centrer sur une hauteur. */
        private static final float HALF_CAP = 11f;

        /** Le duel d'exemple de l'éditeur de HUD, dans l'ordre du résumé du noyau. */
        private static final double[] SAMPLE = sample();

        /** Un résumé complet (toute la longueur du contrat) : le panneau lit aussi le nombre d'événements de chaque famille. */
        private static double[] sample() {
            double[] report = new double[NativeCombat.REPORT_LEN];
            double[] head = { 72, 0.8, 1, 0, 68, 74, 55, 81, 62, 58, 34, 12, 7, 11, 4, 96 };
            System.arraycopy(head, 0, report, 0, head.length);
            for (int i = 0; i < NativeCombat.FAMILY_COUNT - 1; i++) report[NativeCombat.REPORT_MY_COUNTS + i] = 10;
            report[NativeCombat.REPORT_MODE] = 1;
            return report;
        }

        private final double[] shown = new double[NativeCombat.REPORT_LEN];
        private boolean received;
        private long shownAtNanos;

        void show(double[] report) {
            System.arraycopy(report, 0, shown, 0, shown.length);
            shownAtNanos = System.nanoTime();
            received = true;
        }

        @Override
        public float[] naturalSize() {
            return new float[]{ WIDTH, HEIGHT };
        }

        /** En jeu, seulement les secondes qui suivent un duel. L'éditeur de HUD l'affiche toujours (HudElement.hasContent). */
        @Override
        public boolean hasContent() {
            return received && System.nanoTime() - shownAtNanos < SHOWN_NANOS;
        }

        @Override
        public boolean glassCard() {
            return true;
        }

        @Override
        public void draw(UiRenderer r, float x, float y, float w, float h, float s, int vw, int vh) {
            // Jamais de duel noté dans cette partie : on n'est ici que par
            // l'éditeur de HUD, qui a besoin d'un panneau plein pour le placer.
            double[] report = received ? shown : SAMPLE;
            float top = y + h;
            float bottom = drawHeader(r, report, x, x + w, top, s, vw, vh);
            bottom = drawScoreAndFamilies(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
            drawFigures(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
        }

        /** Le bandeau, teinté par le résultat : le nom à gauche, le résultat à droite. Rend son bas. */
        private float drawHeader(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - HEADER_H * s;
            int result = (int) report[NativeCombat.REPORT_RESULT];
            UiColor tone = PvpScoreUi.resultColor(result);
            tinted(r, left, bottom, right, top, RADIUS * s, tone, 0.3f, vw, vh);
            r.drawRoundedRect(left, bottom, left + 3f * s, top, 1.5f * s, tone, vw, vh);

            float center = (top + bottom) / 2f;
            float small = 0.32f * s;
            r.drawText(UiFont.BOLD, Lang.tr("SCORE PVP"), left + 12f * s, center - HALF_CAP * small, UiTheme.TEXT_SECONDARY, small, vw, vh);
            String text = PvpScoreUi.resultText(result).toUpperCase(java.util.Locale.ROOT);
            if (!text.isEmpty()) {
                float scale = 0.46f * s;
                r.drawText(UiFont.BOLD, text, right - 12f * s - r.textWidth(UiFont.BOLD, text, scale), center - HALF_CAP * scale,
                    tone, scale, vw, vh);
            }
            return bottom;
        }

        /** La note dans son cadre, en couleur — ou pourquoi il n'y en a pas — et les six familles à sa droite. Rend le bas de la bande. */
        private float drawScoreAndFamilies(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float height = BODY_H * s;
            float bottom = top - height;

            float boxRight = left + SCORE_W * s;
            float center = (left + boxRight) / 2f;
            float fit = (SCORE_W - 14f) * s;
            double score = report[NativeCombat.REPORT_SCORE];
            UiColor tone = PvpScoreUi.scoreColor(score);
            tinted(r, left, bottom, boxRight, top, RADIUS * s, tone, 0.24f, vw, vh);
            centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - 18f * s, fit, UiTheme.TEXT_MUTED, 0.3f * s, vw, vh);
            if (score >= 0) {
                centered(r, UiFont.BOLD, PvpScoreUi.scoreText(score), center, bottom + 38f * s, fit, tone, 1.25f * s, vw, vh);
                String confidence = Lang.tr("Confiance") + " " + Math.round(report[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
                centered(r, UiFont.REGULAR, confidence, center, bottom + 14f * s, fit, UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
            } else {
                centered(r, UiFont.BOLD, PvpScoreUi.NO_SCORE, center, bottom + 46f * s, fit, tone, 1.1f * s, vw, vh);
                String[] reason = PvpScoreUi.reasonLines(report[NativeCombat.REPORT_REASON]);
                centered(r, UiFont.REGULAR, Lang.tr(reason[0]), center, bottom + 26f * s, fit,
                    UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
                centered(r, UiFont.REGULAR, Lang.tr(reason[1]), center, bottom + 13f * s, fit, UiTheme.TEXT_MUTED, 0.3f * s, vw, vh);
            }

            // Les familles : libellé, barre dans la couleur de sa valeur, valeur.
            float cardLeft = boxRight + GAP * s;
            r.drawRoundedRect(cardLeft, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
            float inner = 10f * s;
            float label = 0.32f * s;
            float barLeft = cardLeft + inner + 88f * s;
            float barRight = right - inner - 24f * s;
            float rowTop = top - 5f * s;
            for (int i = 0; i < NativeCombat.FAMILY_COUNT; i++) {
                float middle = rowTop - ROW * s / 2f;
                rowTop -= ROW * s;
                double family = Math.max(0, Math.min(100, report[NativeCombat.REPORT_FAMILIES + i]));
                // Le niveau de l'adversaire parle de lui, pas de nous : gris,
                // pour qu'il ne se lise pas comme une de nos familles.
                boolean opponent = i == NativeCombat.FAMILY_COUNT - 1;
                // Aucune mesure (aucun smash porté…) : « - », pas un 50 qui se lirait comme une note.
                boolean measured = opponent || report[NativeCombat.REPORT_MY_COUNTS + i] > 0;
                if (!measured) family = 0;
                UiColor familyTone = opponent ? PvpScoreUi.FOE : measured ? PvpScoreUi.scoreColor(family) : PvpScoreUi.NEUTRAL;
                float baseline = middle - HALF_CAP * label;
                r.drawText(Lang.tr(FAMILIES[i]), cardLeft + inner, baseline,
                    opponent ? UiTheme.TEXT_MUTED : UiTheme.TEXT_SECONDARY, label, vw, vh);
                float barY = middle - 2.5f * s;
                float barTop = middle + 2.5f * s;
                r.drawRoundedRect(barLeft, barY, barRight, barTop, 2.5f * s, UiTheme.PANEL_BG, vw, vh);
                float filled = barLeft + (barRight - barLeft) * (float) (family / 100.0);
                if (filled > barLeft + 1f) {
                    r.drawRoundedRect(barLeft, barY, filled, barTop, 2.5f * s, familyTone, vw, vh);
                }
                String value = measured ? String.valueOf(Math.round(family)) : PvpScoreUi.NO_SCORE;
                r.drawText(UiFont.BOLD, value, right - inner - r.textWidth(UiFont.BOLD, value, label), baseline, familyTone, label, vw, vh);
            }
            return bottom;
        }

        /** Les chiffres du duel, en quatre cases égales : la valeur, son nom dessous. */
        private void drawFigures(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - FIGURES_H * s;
            long seconds = Math.round(report[NativeCombat.REPORT_ENGAGED_SECS]);
            String[][] figures = {
                { "CONVERTIES", (int) report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_MY_OPENED] },
                { "SUBIES", (int) report[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_THEIR_OPENED] },
                { "AU CONTACT", seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s" },
                // Le ping est affiché, jamais corrigé.
                { "PING", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : PvpScoreUi.NO_SCORE },
            };
            float gap = 6f * s;
            float cell = (right - left - gap * (figures.length - 1)) / figures.length;
            float fit = cell - 8f * s;
            for (int i = 0; i < figures.length; i++) {
                float x = left + i * (cell + gap);
                r.drawRoundedRect(x, bottom, x + cell, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                float center = x + cell / 2f;
                centered(r, UiFont.BOLD, figures[i][1], center, bottom + 17f * s, fit, UiTheme.TEXT_PRIMARY, 0.42f * s, vw, vh);
                centered(r, UiFont.REGULAR, Lang.tr(figures[i][0]), center, bottom + 6f * s, fit, UiTheme.TEXT_MUTED, 0.25f * s, vw, vh);
            }
        }

        /** Une carte teintée comme sur les pages : la carte grise, puis la couleur en transparence, plus franche à gauche. */
        private static void tinted(UiRenderer r, float x1, float y1, float x2, float y2, float radius, UiColor tone, float strength,
                                   int vw, int vh) {
            r.drawRoundedRect(x1, y1, x2, y2, radius, UiTheme.CARD_BG, vw, vh);
            UiColor strong = tone.withAlpha(strength);
            UiColor faint = tone.withAlpha(strength * 0.25f);
            r.drawGradientRect2D(x1, y1, x2, y2, radius, strong, faint, strong, faint, vw, vh);
        }

        /** Un texte centré sur {@code center}, rétréci s'il dépasse {@code maxWidth} : une traduction plus longue ne sort pas de sa case. */
        private static void centered(UiRenderer r, UiFont font, String text, float center, float y, float maxWidth,
                                     UiColor color, float scale, int vw, int vh) {
            float width = r.textWidth(font, text, scale);
            if (width > maxWidth && width > 0f) {
                scale *= maxWidth / width;
                width = maxWidth;
            }
            r.drawText(font, text, center - width / 2f, y, color, scale, vw, vh);
        }
    }
}
