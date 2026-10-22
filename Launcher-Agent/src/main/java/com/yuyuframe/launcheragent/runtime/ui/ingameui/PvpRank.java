package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;

/**
 * Les rangs du classement compétitif (docs/scoring/classement.md), pour
 * l'interface : noms, couleurs, et ce qu'il reste à faire pour monter. Le
 * calcul des points est dans le noyau ({@code content-core/src/combat/ranked.rs}) ;
 * les constantes d'ici en sont la copie et bougent avec lui.
 *
 * <p>Une position se lit en « points cumulés » : division × 500 + points.
 * C'est ce qui permet d'animer un gain ou une perte d'un seul geste, montée
 * ou descente comprise : la barre se remplit, passe le bord, et la division
 * suivante commence.
 */
public final class PvpRank {

    /** Les rangs, du plus bas au plus haut ; Champion n'a pas de divisions. */
    public static final String[] TIERS = { "Bois", "Pierre", "Fer", "Or", "Diamant", "Netherite", "Champion" };
    public static final int DIVISIONS_PER_TIER = 3;
    /** La première (et seule) division de Champion. */
    public static final int CHAMPION = (TIERS.length - 1) * DIVISIONS_PER_TIER;
    public static final int DIVISION_POINTS = 500;
    public static final int PLACEMENT_DUELS = 10;
    /** Ce que rapporte une victoire de base dans chaque rang ({@code BASE} de ranked.rs). */
    private static final int[] BASE_WIN = { 25, 24, 22, 20, 19, 18, 17 };

    /** La couleur de chaque rang : celle de son matériau. */
    private static final UiColor[] COLORS = {
        new UiColor(196, 142, 88, 255),   // bois
        new UiColor(164, 168, 176, 255),  // pierre
        new UiColor(226, 230, 238, 255),  // fer
        new UiColor(255, 206, 72, 255),   // or
        new UiColor(96, 232, 236, 255),   // diamant
        new UiColor(172, 132, 176, 255),  // netherite
        new UiColor(244, 112, 228, 255),  // champion
    };

    /**
     * La part des joueurs dans chaque rang, en pourcentage — une
     * <b>estimation</b>, celle que l'échelle vise (la plupart en Pierre et
     * Fer, peu en Netherite, très peu en Champion), pas une mesure : le
     * classement est calculé sur le PC de chacun, personne ne connaît les
     * rangs des autres. L'écran le dit (« répartition estimée »). À remplacer
     * par la vraie répartition si les rangs passent un jour par le serveur.
     */
    public static final double[] ESTIMATED_SHARE = { 15, 22, 24, 18, 12, 7, 2 };

    /** Largeur d'un rang sur l'axe de la répartition, en divisions : trois, Champion compris. */
    public static final int AXIS_DIVISIONS = TIERS.length * DIVISIONS_PER_TIER;

    /** Lissage de la courbe, en divisions. */
    private static final double SMOOTHING = 1.3;

    /**
     * La hauteur de la courbe de répartition à une position de l'axe (en
     * divisions, de 0 à {@link #AXIS_DIVISIONS}), avant normalisation : chaque
     * division porte un tiers de la part de son rang, lissée en cloche. En
     * Champion, la part décroît d'une division à l'autre.
     */
    public static double density(double x) {
        double sum = 0;
        for (int d = 0; d < AXIS_DIVISIONS; d++) {
            int tier = d / DIVISIONS_PER_TIER;
            double weight = ESTIMATED_SHARE[tier] / DIVISIONS_PER_TIER;
            if (tier == TIERS.length - 1) weight = ESTIMATED_SHARE[tier] * new double[]{ 0.6, 0.3, 0.1 }[d % DIVISIONS_PER_TIER];
            double u = (x - (d + 0.5)) / SMOOTHING;
            sum += weight * Math.exp(-0.5 * u * u);
        }
        return sum;
    }

    /** La position d'un joueur sur l'axe, en divisions (Champion : ses points comptent pour trois divisions de 500). */
    public static double axisPosition(int division, double points) {
        if (division >= CHAMPION) {
            return Math.min(AXIS_DIVISIONS, CHAMPION + points / DIVISION_POINTS);
        }
        return division + Math.min(1.0, points / DIVISION_POINTS);
    }

    /** La part des joueurs (estimée, 0 à 100) sous une position de l'axe. */
    public static double percentBelow(double position) {
        int steps = 400;
        double below = 0, total = 0;
        for (int i = 0; i < steps; i++) {
            double x = (i + 0.5) * AXIS_DIVISIONS / steps;
            double d = density(x);
            total += d;
            if (x < position) below += d;
        }
        return total <= 0 ? 0 : 100.0 * below / total;
    }

    private PvpRank() {
    }

    public static int tier(int division) {
        return Math.max(0, Math.min(TIERS.length - 1, division / DIVISIONS_PER_TIER));
    }

    /** « Or 2 », « Champion ». */
    public static String name(int division) {
        int tier = tier(division);
        String name = Lang.tr(TIERS[tier]);
        return division >= CHAMPION ? name : name + " " + (division % DIVISIONS_PER_TIER + 1);
    }

    public static UiColor color(int division) {
        return COLORS[tier(division)];
    }

    /** La division d'une position en points cumulés. */
    public static int divisionAt(double cumulative) {
        return (int) Math.max(0, Math.min(CHAMPION, Math.floor(cumulative / DIVISION_POINTS)));
    }

    /** Les points dans la division, pour une position en points cumulés (sans plafond en Champion). */
    public static double pointsAt(double cumulative) {
        return Math.max(0, cumulative - divisionAt(cumulative) * (double) DIVISION_POINTS);
    }

    public static double cumulative(int division, double points) {
        return division * (double) DIVISION_POINTS + points;
    }

    /**
     * Combien de victoires, environ, avant la division suivante : les points
     * qui manquent, divisés par une victoire de base du rang. Une estimation
     * pour donner envie, pas une promesse (le jeu et l'adversaire font varier
     * chaque gain). −1 en Champion, qui n'a pas de suivante.
     */
    public static int winsToNext(int division, double points) {
        if (division >= CHAMPION) return -1;
        double missing = Math.max(1, DIVISION_POINTS - points);
        return (int) Math.ceil(missing / BASE_WIN[tier(division)]);
    }

    /** « Encore ~6 victoires avant Bois 2 » ; vide en Champion. Pas de « ≈ » : la police ne l'a pas. */
    public static String nextGoal(int division, double points) {
        int wins = winsToNext(division, points);
        if (wins < 0) return "";
        return Lang.tr("Encore") + " ~" + wins + " " + Lang.tr(wins > 1 ? "victoires avant" : "victoire avant") + " " + name(division + 1);
    }

    /**
     * L'insigne d'un rang : un carré arrondi dans la couleur du matériau, du
     * plus clair en haut au plus sombre en bas, la division au milieu (rien en
     * Champion). {@code glow} de 0 à 1 l'auréole, pour une montée.
     */
    public static void badge(UiRenderer r, int division, float x, float y, float size, float glow, float alpha, int vw, int vh) {
        UiColor tone = color(division);
        if (glow > 0.01f) {
            r.drawGlow(x, y, x + size, y + size, size * 0.28f, glow, tone.multiplyAlpha(alpha), vw, vh);
        }
        UiColor top = UiColor.lerp(tone, new UiColor(255, 255, 255, 255), 0.25f).multiplyAlpha(alpha);
        UiColor bottom = UiColor.lerp(tone, new UiColor(0, 0, 0, 255), 0.45f).multiplyAlpha(alpha);
        r.drawGradientRect(x, y, x + size, y + size, size * 0.24f, bottom, top, vw, vh);
        // Un liseré plus clair sur le haut : le relief d'un lingot.
        r.drawRoundedRect(x + size * 0.12f, y + size * 0.78f, x + size * 0.88f, y + size * 0.86f, size * 0.04f,
            new UiColor(255, 255, 255, 70).multiplyAlpha(alpha), vw, vh);
        if (division < CHAMPION) {
            String digit = String.valueOf(division % DIVISIONS_PER_TIER + 1);
            // Un texte monte d'environ 22 × l'échelle au-dessus de sa ligne de
            // base : le chiffre fait la moitié de l'insigne, centré.
            float scale = size / 44f;
            float width = r.textWidth(UiFont.BOLD, digit, scale);
            r.drawText(UiFont.BOLD, digit, x + (size - width) / 2f, y + size * 0.25f,
                new UiColor(20, 18, 26, 230).multiplyAlpha(alpha), scale, vw, vh);
        }
    }
}
