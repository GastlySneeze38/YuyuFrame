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
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpRank;
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
 * moyenne des notes, puis les duels, chacun menant à son détail. Son seul
 * réglage, « Partager mes duels », est un interrupteur de cette page.
 *
 * <p>Éteint par défaut : tant que le score n'est pas calibré, personne ne
 * doit se retrouver avec des journaux écrits sans l'avoir demandé.
 */
public final class PvpScoreModule extends LauncherModule implements HudElementOwner {

    /** Les versions dont les mixins de combat existent ({@code apimixin/<version>/combat}). */
    public static final java.util.Set<String> VERSIONS = java.util.Set.of("26.1.2", "26.3");

    /** Libellés des six familles, dans l'ordre de {@link NativeCombat#REPORT_FAMILIES}. */
    public static final String[] FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle vertical et horizontal", "Ressources", "Niveau adverse",
    };
    /** Les mêmes, en court, pour les colonnes étroites du panneau de fin de duel. */
    public static final String[] SHORT_FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle", "Ressources", "Niveau adverse",
    };
    /**
     * Les familles de l'épée (2026-10-22), dans les mêmes cases du résumé. La
     * cinquième n'existe pas : le kit noté n'a que l'épée, il n'y a pas de
     * ressources à juger.
     */
    public static final String[] SWORD_FAMILIES = {
        "Combos", "Qualité du coup", "Défense", "Placement", "", "Niveau adverse",
    };
    public static final String[] SWORD_SHORT_FAMILIES = {
        "Combos", "Qualité du coup", "Défense", "Placement", "", "Niveau adverse",
    };

    /** Un duel à l'épée ({@link NativeCombat#REPORT_MODE} 2) ? */
    public static boolean isSword(double[] report) {
        return report[NativeCombat.REPORT_MODE] == 2;
    }

    /** Le libellé de la famille {@code i} dans le mode de ce duel. */
    public static String familyName(int i, double[] report) {
        return (isSword(report) ? SWORD_FAMILIES : FAMILIES)[i];
    }

    public static String shortFamilyName(int i, double[] report) {
        return (isSword(report) ? SWORD_SHORT_FAMILIES : SHORT_FAMILIES)[i];
    }

    /** Combien de mes familles ce duel a : cinq à la masse, quatre à l'épée. */
    public static int familyRows(double[] report) {
        return isSword(report) ? 4 : NativeCombat.FAMILY_COUNT - 1;
    }
    /** Libellés des résultats, par code ({@link NativeCombat#REPORT_RESULT}). */
    public static final String[] RESULTS = { "", "Victoire", "Défaite", "Match nul", "" };

    private final Summary summary = new Summary();
    private final HudElement element = new HudElement("pvp-score", "Score PvP", HudAnchor.TOP_CENTER, 0f, 36f,
        (HudElement.CustomRenderer) summary);

    private final double[] incoming = new double[NativeCombat.REPORT_LEN];
    /** Le numéro du dernier résumé reçu — celui d'avant le lancement n'est pas montré. */
    private int lastReport = NativeCombat.report(new double[NativeCombat.REPORT_LEN]);
    private final double[] incomingRanked = new double[NativeCombat.RANKED_LEN];
    /** Le numéro du résumé dont on a reçu les points de classement. */
    private int lastRanked = NativeCombat.ranked(new double[NativeCombat.RANKED_LEN]);

    /** Le module, pour la page de l'historique qui porte son interrupteur de partage. */
    private static volatile PvpScoreModule instance;

    public static PvpScoreModule instance() {
        return instance;
    }

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
        instance = this;
        // La valeur par défaut part au noyau tout de suite : un réglage jamais
        // touché n'est pas relu du fichier, donc aucun setter ne la donnerait.
        // Une valeur sauvegardée arrive ensuite et la remplace.
        NativeCombat.share(shareDuels);
    }

    @Override
    public HudElement hudElement() {
        return element;
    }

    /**
     * « Partager mes duels » : chaque duel fermé part, anonymisé, vers le
     * serveur de YuyuFrame pour calibrer la note (voir
     * {@code content-core/src/combat/share.rs} et docs/scoring/partage.md).
     * Allumé par défaut (décision du 2026-10-18 : la calibration a besoin des
     * duels de tout le monde), et rien ne part sans compte YuyuFrame connecté.
     *
     * <p>Le module n'a pas d'écran de réglages — sa carte ouvre l'historique :
     * l'interrupteur est sur cette page ({@code UiPvpScoreScreen}). Le
     * réglage est déclaré ici pour être sauvegardé comme les autres.
     */
    private boolean shareDuels = true;

    public boolean shareDuels() {
        return shareDuels;
    }

    public void setShareDuels(boolean value) {
        shareDuels = value;
        NativeCombat.share(value);
        com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save();
    }

    @Override
    protected void settings(com.yuyuframe.launcheragent.runtime.ui.config.SettingList s) {
        s.toggle("shareDuels", "Partager mes duels",
            "Envoie vos duels, anonymisés, pour calibrer la note — sans pseudo, ni UUID, ni adresse de serveur",
            "Calibration", null, () -> shareDuels, v -> { shareDuels = v; NativeCombat.share(v); });
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
        if (enabled) HudRegistry.register(element);
        else HudRegistry.unregister(element);
    }

    /**
     * À chaque image : un nouveau résumé est-il arrivé du noyau ? Puis ses
     * points de classement, qui le suivent de peu (le noyau rejoue
     * l'historique sur un fil à part).
     */
    @Override
    public void onTick() {
        int number = NativeCombat.report(incoming);
        if (number != lastReport) {
            lastReport = number;
            summary.show(incoming, number);
        }
        int ranked = NativeCombat.ranked(incomingRanked);
        if (ranked != lastRanked) {
            lastRanked = ranked;
            summary.ranked(ranked, incomingRanked);
        }
    }

    /**
     * Le panneau de fin de duel.
     *
     * <p><b>Duel classé</b> (le compétitif, docs/scoring/classement.md) : ce
     * qui compte est ce qu'on a gagné, et l'envie d'en relancer un. Sous le
     * résultat, les points du duel qui défilent (« +31 ») à côté de la note,
     * puis le rang : son insigne, la barre de la division qui se remplit ou se
     * vide — et passe à la division suivante en cas de montée, l'insigne
     * s'illuminant —, et combien de victoires il reste avant la suivante. Ni
     * familles ni chiffres : ils sont sur la page du duel.
     *
     * <p><b>Duel non classé</b> (pas noté, mode sans classement) : trois
     * bandes, de haut en bas : le résultat ; la note — ou pourquoi il n'y en a
     * pas — à côté des familles ; les chiffres du duel. Mêmes couleurs que les
     * pages de l'historique.
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
            double[] head = { 342, 0.8, 1, 0, 318, 356, 262, 395, 281, 290, 34, 12, 7, 11, 4, 96 };
            System.arraycopy(head, 0, report, 0, head.length);
            for (int i = 0; i < NativeCombat.FAMILY_COUNT - 1; i++) report[NativeCombat.REPORT_MY_COUNTS + i] = 10;
            report[NativeCombat.REPORT_MODE] = 1;
            report[NativeCombat.REPORT_BASE_SCORE] = 322;
            report[NativeCombat.REPORT_BUG_USE] = 20;
            return report;
        }

        /** Les points de l'exemple de l'éditeur : une victoire qui fait monter d'Or 1 à Or 2. */
        private static final double[] SAMPLE_RANKED = { 31, 20, 6, 1.2, 1, 9, 478, 40, 10, 9, 41, 1 };
        /** Le temps d'une boucle de l'animation d'exemple, dans l'éditeur de HUD. */
        private static final long SAMPLE_LOOP_NANOS = 4_000_000_000L;

        private final double[] shown = new double[NativeCombat.REPORT_LEN];
        private boolean received;
        private long shownAtNanos;
        private int shownNumber;
        private final double[] ranked = new double[NativeCombat.RANKED_LEN];
        /** Les points de classement du duel affiché sont arrivés. */
        private boolean rankedReceived;
        private long rankedAtNanos;

        void show(double[] report, int number) {
            System.arraycopy(report, 0, shown, 0, shown.length);
            shownAtNanos = System.nanoTime();
            shownNumber = number;
            received = true;
            rankedReceived = false;
        }

        /** Les points de classement du résumé {@code number} — ignorés s'ils ne sont pas ceux du duel affiché. */
        void ranked(int number, double[] values) {
            if (!received || number != shownNumber) return;
            System.arraycopy(values, 0, ranked, 0, ranked.length);
            rankedReceived = true;
            rankedAtNanos = System.nanoTime();
            // Le panneau reste le temps de voir l'animation entière.
            shownAtNanos = Math.max(shownAtNanos, rankedAtNanos - SHOWN_NANOS + RANKED_SHOWN_NANOS);
        }

        /** Après l'arrivée des points : de quoi voir défiler le gain et la barre. */
        private static final long RANKED_SHOWN_NANOS = 10_000_000_000L;

        /** Ce duel compte-t-il au classement ? Noté, avec un verdict, à la masse ou à l'épée. */
        private static boolean rankable(double[] report) {
            int result = (int) report[NativeCombat.REPORT_RESULT];
            int mode = (int) report[NativeCombat.REPORT_MODE];
            return report[NativeCombat.REPORT_SCORE] >= 0 && result >= 1 && result <= 3 && (mode == 1 || mode == 2);
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
            if (!received) {
                // L'exemple de l'éditeur rejoue l'animation en boucle.
                long since = System.nanoTime() % SAMPLE_LOOP_NANOS;
                drawRanked(r, report, SAMPLE_RANKED, since / 1e9f, x, x + w, bottom - GAP * s, s, vw, vh);
            } else if (rankable(report)) {
                // Les points arrivent un instant après le résumé : en attendant,
                // la même mise en page, points en attente.
                float since = rankedReceived ? (System.nanoTime() - rankedAtNanos) / 1e9f : -1f;
                drawRanked(r, report, rankedReceived ? ranked : null, since, x, x + w, bottom - GAP * s, s, vw, vh);
            } else {
                bottom = drawScoreAndFamilies(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
                drawFigures(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
            }
        }

        private static float ease(float since, float delay, float duration) {
            float t = Math.max(0f, Math.min(1f, (since - delay) / duration));
            float u = 1f - t;
            return 1f - u * u * u;
        }

        /**
         * Le duel classé : les points et la note, puis le rang qui progresse.
         * {@code values} nul tant que les points ne sont pas arrivés ;
         * {@code since} : secondes depuis leur arrivée.
         */
        private void drawRanked(UiRenderer r, double[] report, double[] values, float since, float left, float right, float top,
                                float s, int vw, int vh) {
            float rowA = 66f * s;
            float gap = GAP * s;
            float aBottom = top - rowA;

            // La note, petite : elle explique le gain, elle n'est plus le sujet.
            float boxRight = left + 92f * s;
            float center = (left + boxRight) / 2f;
            double score = report[NativeCombat.REPORT_SCORE];
            UiColor scoreTone = PvpScoreUi.scoreColor(score);
            tinted(r, left, aBottom, boxRight, top, RADIUS * s, scoreTone, 0.2f, vw, vh);
            centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - 17f * s, 78f * s, UiTheme.TEXT_MUTED, 0.28f * s, vw, vh);
            centered(r, UiFont.BOLD, PvpScoreUi.scoreText(score), center, aBottom + 14f * s, 78f * s, scoreTone, 0.95f * s, vw, vh);

            // Les points du duel, en grand : c'est ce qu'on regarde d'abord.
            float cardLeft = boxRight + gap;
            double total = values == null ? 0 : values[NativeCombat.RANKED_TOTAL];
            UiColor tone = values == null ? UiTheme.TEXT_SECONDARY : total > 0 ? PvpScoreUi.GOOD : total < 0 ? PvpScoreUi.BAD : UiTheme.TEXT_SECONDARY;
            tinted(r, cardLeft, aBottom, right, top, RADIUS * s, tone, values == null ? 0.08f : 0.28f, vw, vh);
            float inner = 12f * s;
            r.drawText(UiFont.BOLD, Lang.tr("CLASSEMENT"), cardLeft + inner, top - 17f * s, UiTheme.TEXT_MUTED, 0.28f * s, vw, vh);
            if (values == null) {
                r.drawText(UiFont.REGULAR, Lang.tr("Calcul des points…"), cardLeft + inner, aBottom + 22f * s, UiTheme.TEXT_SECONDARY,
                    0.36f * s, vw, vh);
            } else if (values[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS) {
                // Un duel de placement : ses points restent cachés jusqu'au rang
                // de départ, on montre le verdict et où en est le placement.
                int result = (int) report[NativeCombat.REPORT_RESULT];
                UiColor verdict = PvpScoreUi.resultColor(result);
                float pop = 1f + 0.18f * (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, (since - 0.2f) / 0.3f)));
                r.drawText(UiFont.BOLD, PvpScoreUi.resultText(result).toUpperCase(java.util.Locale.ROOT), cardLeft + inner,
                    aBottom + 20f * s, verdict, 0.9f * s * pop, vw, vh);
                boolean counted = values[NativeCombat.RANKED_AFTER_DUELS] > values[NativeCombat.RANKED_BEFORE_DUELS];
                int done = (int) values[NativeCombat.RANKED_AFTER_DUELS];
                String line = !counted ? Lang.tr("Ne compte pas : déjà 2 duels contre lui au placement")
                    : done >= PvpRank.PLACEMENT_DUELS ? Lang.tr("Placement terminé")
                    : Lang.tr("Duel de placement") + " " + done + " / " + PvpRank.PLACEMENT_DUELS;
                r.drawText(UiFont.REGULAR, line, cardLeft + inner, aBottom + 7f * s, UiTheme.TEXT_MUTED, 0.24f * s, vw, vh);
            } else {
                long shownTotal = Math.round(total * ease(since, 0.1f, 0.8f));
                String points = (shownTotal > 0 ? "+" : shownTotal < 0 ? "-" : "") + Math.abs(shownTotal);
                // Un petit rebond à l'arrivée du chiffre.
                float pop = 1f + 0.18f * (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, (since - 0.75f) / 0.3f)));
                float big = 1.3f * s * pop;
                r.drawText(UiFont.BOLD, points, cardLeft + inner, aBottom + 18f * s, tone, big, vw, vh);
                float after = cardLeft + inner + r.textWidth(UiFont.BOLD, points, big) + 6f * s;
                r.drawText(UiFont.BOLD, "RR", after, aBottom + 18f * s, tone.multiplyAlpha(0.8f), 0.42f * s, vw, vh);
                // D'où viennent les points, en une ligne.
                r.drawText(UiFont.REGULAR, breakdown(values, (int) report[NativeCombat.REPORT_RESULT]), cardLeft + inner, aBottom + 7f * s,
                    UiTheme.TEXT_MUTED, 0.24f * s, vw, vh);
            }

            drawProgress(r, values, (int) report[NativeCombat.REPORT_RESULT], since, left, right, aBottom - gap, s, vw, vh);
        }

        /**
         * « Victoire +20 · Jeu +6 · Adversaire x1,20 » (et « même adversaire
         * x0,50 » s'il y a lieu). Ni « × » ni « − » : la police ne les a pas.
         */
        private static String breakdown(double[] v, int result) {
            String outcome = Lang.tr(result == 1 ? "Victoire" : result == 2 ? "Défaite" : "Nul");
            StringBuilder text = new StringBuilder(outcome).append(' ').append(signed(v[NativeCombat.RANKED_BASE]))
                .append("  ·  ").append(Lang.tr("Jeu")).append(' ').append(signed(v[NativeCombat.RANKED_PLAY]))
                .append("  ·  ").append(Lang.tr("Adversaire")).append(" x")
                .append(String.format(java.util.Locale.FRANCE, "%.2f", v[NativeCombat.RANKED_FOE]));
            if (v[NativeCombat.RANKED_REPEAT] < 0.999) {
                text.append("  ·  ").append(Lang.tr("même adversaire")).append(" x")
                    .append(String.format(java.util.Locale.FRANCE, "%.2f", v[NativeCombat.RANKED_REPEAT]));
            }
            return text.toString();
        }

        private static String signed(double value) {
            long rounded = Math.round(value);
            return (rounded > 0 ? "+" : "") + rounded;
        }

        /**
         * Le rang, qui progresse : l'insigne, le nom, la barre de la division
         * animée de la position d'avant à celle d'après — en points cumulés,
         * donc une montée passe le bord et recommence à la division suivante —,
         * et ce qu'il reste à faire. Pendant le placement : dix cases, celle
         * de ce duel dans la couleur de son verdict ({@code result}).
         */
        private void drawProgress(UiRenderer r, double[] v, int result, float since, float left, float right, float top, float s,
                                  int vw, int vh) {
            float bottom = top - 70f * s;
            float inner = 12f * s;
            if (v == null) {
                r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                return;
            }
            int beforeDivision = (int) v[NativeCombat.RANKED_BEFORE_DIVISION];
            int afterDivision = (int) v[NativeCombat.RANKED_AFTER_DIVISION];
            boolean placed = v[NativeCombat.RANKED_PLACED] >= 1;

            if (!placed) {
                // Le placement : le rang est caché, on voit les duels qui restent.
                r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                r.drawText(UiFont.BOLD, Lang.tr("NON CLASSÉ"), left + inner, top - 22f * s, UiTheme.TEXT_PRIMARY, 0.5f * s, vw, vh);
                int done = (int) v[NativeCombat.RANKED_AFTER_DUELS];
                boolean counted = done > (int) v[NativeCombat.RANKED_BEFORE_DUELS];
                float pipGap = 4f * s;
                float pipW = (right - left - inner * 2f - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
                float pipY = bottom + 26f * s;
                for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                    float x = left + inner + i * (pipW + pipGap);
                    boolean filled = i < done;
                    boolean current = counted && i == done - 1;
                    // La case de ce duel s'allume à son tour, dans la couleur de son verdict.
                    float light = current ? ease(since, 0.6f, 0.6f) : 1f;
                    UiColor lit = current ? PvpScoreUi.resultColor(result) : UiTheme.ACCENT;
                    UiColor color = filled ? UiColor.lerp(UiTheme.PANEL_BG, lit, light) : UiTheme.PANEL_BG;
                    r.drawRoundedRect(x, pipY, x + pipW, pipY + 8f * s, 3f * s, color, vw, vh);
                }
                int remaining = Math.max(0, PvpRank.PLACEMENT_DUELS - done);
                String goal = Lang.tr("Rang dévoilé dans") + " " + remaining + " " + Lang.tr(remaining > 1 ? "duels" : "duel");
                r.drawText(UiFont.REGULAR, goal, left + inner, bottom + 9f * s, UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
                return;
            }

            double from = PvpRank.cumulative(beforeDivision, v[NativeCombat.RANKED_BEFORE_POINTS]);
            double to = PvpRank.cumulative(afterDivision, v[NativeCombat.RANKED_AFTER_POINTS]);
            // Le rang vient d'être dévoilé (fin du placement) : pas de trajet, il apparaît.
            boolean revealed = v[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS;
            double now = revealed ? to : from + (to - from) * ease(since, 0.5f, 1.4f);
            int division = PvpRank.divisionAt(now);
            double points = PvpRank.pointsAt(now);
            UiColor rank = PvpRank.color(division);
            boolean promoted = division > beforeDivision && !revealed;
            boolean demoted = division < beforeDivision;
            // L'éclat d'une montée ou d'un rang dévoilé : fort à l'instant, puis qui respire.
            float glow = 0f;
            if (promoted || revealed) {
                float burst = Math.max(0f, 1f - (since - 1.2f) / 1.5f);
                glow = Math.min(1f, 0.35f + burst + 0.15f * (float) Math.sin(since * 4f));
            }
            tinted(r, left, bottom, right, top, RADIUS * s, rank, 0.22f + 0.18f * glow, vw, vh);

            float badge = 44f * s;
            float badgeX = left + inner;
            float badgeY = bottom + (70f * s - badge) / 2f;
            PvpRank.badge(r, division, badgeX, badgeY, badge, glow, 1f, vw, vh);

            float textLeft = badgeX + badge + 12f * s;
            float textRight = right - inner;
            r.drawText(UiFont.BOLD, PvpRank.name(division).toUpperCase(java.util.Locale.ROOT), textLeft, top - 24f * s, rank,
                0.52f * s, vw, vh);
            String status = revealed ? Lang.tr("RANG DÉVOILÉ !") : promoted ? Lang.tr("PROMOTION !") : demoted ? Lang.tr("Rétrogradé") : "";
            if (!status.isEmpty()) {
                float scale = 0.34f * s;
                UiColor statusTone = demoted ? PvpScoreUi.BAD : rank;
                r.drawText(UiFont.BOLD, status, textRight - r.textWidth(UiFont.BOLD, status, scale), top - 22f * s, statusTone, scale, vw, vh);
            }

            // La barre de la division ; en Champion, pleine.
            float barY = bottom + 25f * s;
            float barH = 8f * s;
            r.drawRoundedRect(textLeft, barY, textRight, barY + barH, barH / 2f, UiTheme.PANEL_BG, vw, vh);
            double fraction = division >= PvpRank.CHAMPION ? 1.0 : Math.min(1.0, points / PvpRank.DIVISION_POINTS);
            float filled = textLeft + (textRight - textLeft) * (float) fraction;
            if (filled > textLeft + 1f) {
                r.drawGradientRect2D(textLeft, barY, filled, barY + barH, barH / 2f,
                    UiColor.lerp(rank, new UiColor(0, 0, 0, 255), 0.25f), rank,
                    UiColor.lerp(rank, new UiColor(0, 0, 0, 255), 0.25f), rank, vw, vh);
            }

            String count = division >= PvpRank.CHAMPION
                ? Math.round(points) + " " + Lang.tr("points")
                : Math.round(points) + " / " + PvpRank.DIVISION_POINTS;
            r.drawText(UiFont.BOLD, count, textLeft, bottom + 9f * s, UiTheme.TEXT_PRIMARY, 0.3f * s, vw, vh);
            String goal = PvpRank.nextGoal(division, points);
            if (!goal.isEmpty()) {
                float scale = 0.27f * s;
                float width = Math.min(r.textWidth(UiFont.REGULAR, goal, scale), textRight - textLeft - 60f * s);
                centeredRight(r, goal, textRight, bottom + 9f * s, width, scale, vw, vh);
            }
        }

        /** Un texte calé à droite sur {@code right}, rétréci s'il dépasse {@code maxWidth}. */
        private static void centeredRight(UiRenderer r, String text, float right, float y, float maxWidth, float scale, int vw, int vh) {
            float width = r.textWidth(UiFont.REGULAR, text, scale);
            if (width > maxWidth && width > 0f) {
                scale *= maxWidth / width;
                width = maxWidth;
            }
            r.drawText(UiFont.REGULAR, text, right - width, y, UiTheme.TEXT_SECONDARY, scale, vw, vh);
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
                // Le bonus bug use, à part : « 322 + 20 bug use » sous la note.
                double bonus = report[NativeCombat.REPORT_BUG_USE];
                boolean withBonus = bonus >= 0.5;
                centered(r, UiFont.BOLD, PvpScoreUi.scoreText(score), center, bottom + (withBonus ? 46f : 38f) * s, fit, tone, 1.25f * s, vw, vh);
                if (withBonus) {
                    centered(r, UiFont.BOLD, PvpScoreUi.bugUseText(report[NativeCombat.REPORT_BASE_SCORE], bonus), center, bottom + 27f * s, fit,
                        PvpScoreUi.BUG_USE, 0.3f * s, vw, vh);
                }
                String confidence = Lang.tr("Confiance") + " " + Math.round(report[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
                centered(r, UiFont.REGULAR, confidence, center, bottom + (withBonus ? 11f : 14f) * s, fit, UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
            } else {
                centered(r, UiFont.BOLD, PvpScoreUi.NO_SCORE, center, bottom + 46f * s, fit, tone, 1.1f * s, vw, vh);
                String[] reason = PvpScoreUi.reasonLines(report);
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
                // À l'épée, pas de cinquième famille : la ligne n'est pas dessinée.
                if (i >= familyRows(report) && i < NativeCombat.FAMILY_COUNT - 1) continue;
                float middle = rowTop - ROW * s / 2f;
                rowTop -= ROW * s;
                double family = Math.max(0, Math.min(NativeCombat.SCORE_MAX, report[NativeCombat.REPORT_FAMILIES + i]));
                // Le niveau de l'adversaire parle de lui, pas de nous : gris,
                // pour qu'il ne se lise pas comme une de nos familles.
                boolean opponent = i == NativeCombat.FAMILY_COUNT - 1;
                // Aucune mesure (aucun smash porté…) : « - », pas un 250 qui se lirait comme une note.
                boolean measured = opponent || report[NativeCombat.REPORT_MY_COUNTS + i] > 0;
                if (!measured) family = 0;
                UiColor familyTone = opponent ? PvpScoreUi.FOE : measured ? PvpScoreUi.scoreColor(family) : PvpScoreUi.NEUTRAL;
                float baseline = middle - HALF_CAP * label;
                r.drawText(Lang.tr(shortFamilyName(i, report)), cardLeft + inner, baseline,
                    opponent ? UiTheme.TEXT_MUTED : UiTheme.TEXT_SECONDARY, label, vw, vh);
                float barY = middle - 2.5f * s;
                float barTop = middle + 2.5f * s;
                r.drawRoundedRect(barLeft, barY, barRight, barTop, 2.5f * s, UiTheme.PANEL_BG, vw, vh);
                float filled = barLeft + (barRight - barLeft) * (float) (family / NativeCombat.SCORE_MAX);
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
            // La durée du duel, pas le seul temps au contact.
            long seconds = Math.round(report[NativeCombat.REPORT_DURATION_SECS]);
            // L'épée n'a pas d'opportunités de smash : ses deux premières cases
            // sont le W-tap et le plus long combo, le cœur du mode.
            String[][] figures = isSword(report) ? new String[][] {
                { "W-TAPS", (int) report[NativeCombat.REPORT_SWORD_WTAPS_LANDED] + " / " + (int) report[NativeCombat.REPORT_SWORD_WTAPS] },
                { "PLUS LONG COMBO", String.valueOf((int) report[NativeCombat.REPORT_SWORD_LONGEST_COMBO]) },
                { "DURÉE", seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s" },
                { "PING", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : PvpScoreUi.NO_SCORE },
            } : new String[][] {
                { "CONVERTIES", (int) report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_MY_OPENED] },
                { "SUBIES", (int) report[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_THEIR_OPENED] },
                { "DURÉE", seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s" },
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
