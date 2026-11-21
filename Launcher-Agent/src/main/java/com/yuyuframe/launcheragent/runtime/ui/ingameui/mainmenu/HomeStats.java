package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Le compétitif de la carte de joueur, lu une fois à l'ouverture du menu dans
 * l'historique des duels de cette instance, mode par mode (masse, épée) : le
 * rang, la note moyenne, la série en cours, le nombre de duels, le taux de
 * victoire et les derniers duels. Sans duel, chaque chiffre dit qu'il n'y a
 * encore rien (« - ») plutôt qu'un zéro trompeur.
 */
public final class HomeStats {

    /** Codes des modes de l'historique, dans l'ordre de la carte (voir {@code PvpScoreUi.MODE_CODES}). */
    static final int[] MODE_CODES = { 1, 2 };
    static final String[] MODE_NAMES = { "Masse", "Épée" };

    /** Valeur de {@code NativeCombat.REPORT_RESULT} pour un duel gagné, puis perdu. */
    private static final int WIN = 1, LOSS = 2;
    /** Nombre de duels récents montrés sur la carte. */
    static final int RECENT = 7;

    /** Un duel récent : gagné ou non, et sa note (négative = pas de note). */
    public static final class Recent {
        public final boolean won;
        public final boolean lost;
        public final int score;

        Recent(boolean won, boolean lost, int score) {
            this.won = won;
            this.lost = lost;
            this.score = score;
        }
    }

    /** Les chiffres d'un mode. */
    public static final class Mode {
        /** {@code null} = aucun duel classé dans ce mode. */
        public final DuelHistory.Rank rank;
        public final String average, streak, count, winRate;
        public final List<Recent> recent;

        Mode(DuelHistory.Rank rank, String average, String streak, String count, String winRate, List<Recent> recent) {
            this.rank = rank;
            this.average = average;
            this.streak = streak;
            this.count = count;
            this.winRate = winRate;
            this.recent = recent;
        }
    }

    public final Mode[] modes;
    /** Le mode montré à l'ouverture : le mieux classé, sinon la masse. */
    public final int preferred;

    private HomeStats(Mode[] modes, int preferred) {
        this.modes = modes;
        this.preferred = preferred;
    }

    /** À partir de la dernière lecture de l'historique ({@link DuelHistory#cached()}) : instantané, jamais le noyau. */
    public static HomeStats load() {
        try {
            DuelHistory history = DuelHistory.cached();
            Mode[] modes = new Mode[MODE_CODES.length];
            int preferred = 0, best = -1;
            for (int i = 0; i < MODE_CODES.length; i++) {
                modes[i] = mode(history, MODE_CODES[i]);
                DuelHistory.Rank r = modes[i].rank;
                if (r != null && r.placed && r.division > best) {
                    best = r.division;
                    preferred = i;
                }
            }
            return new HomeStats(modes, preferred);
        } catch (Throwable t) {
            LauncherLog.err("[HomeStats] historique des duels illisible : " + t);
            Mode empty = new Mode(null, "-", "-", "0", "-", Collections.emptyList());
            return new HomeStats(new Mode[]{ empty, empty }, 0);
        }
    }

    private static Mode mode(DuelHistory history, int code) {
        // Du plus récent au plus ancien.
        List<DuelHistory.Duel> duels = new ArrayList<>();
        for (DuelHistory.Duel d : history.duels) if (d.mode() == code) duels.add(d);

        double sum = 0;
        int scored = 0, wins = 0, decided = 0, streak = 0;
        boolean streakOpen = true;
        List<Recent> recent = new ArrayList<>();
        for (DuelHistory.Duel d : duels) {
            double score = d.score();
            if (score > 0 && !Double.isNaN(score)) {
                sum += score;
                scored++;
            }
            int result = d.result();
            if (result == WIN || result == LOSS) {
                decided++;
                if (result == WIN) wins++;
            }
            if (streakOpen) {
                if (result == WIN) streak++;
                else streakOpen = false;
            }
            if (recent.size() < RECENT) {
                recent.add(new Recent(result == WIN, result == LOSS, score > 0 && !Double.isNaN(score) ? (int) Math.round(score) : -1));
            }
        }
        String average = scored > 0 ? String.valueOf(Math.round(sum / scored)) : "-";
        String winRate = decided > 0 ? Math.round(100.0 * wins / decided) + " %" : "-";
        return new Mode(history.rank(code), average, duels.isEmpty() ? "-" : String.valueOf(streak),
            String.valueOf(duels.size()), winRate, recent);
    }
}
