package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpRank;

/**
 * Les trois chiffres de l'accueil, lus une fois à l'ouverture du menu dans
 * l'historique des duels de cette instance : le meilleur rang classé, la
 * note moyenne, et la série de victoires en cours. Sans duel, chacun dit
 * qu'il n'y a encore rien plutôt qu'un zéro trompeur.
 */
public final class HomeStats {

    public final String rank;
    public final String average;
    public final String streak;

    private HomeStats(String rank, String average, String streak) {
        this.rank = rank;
        this.average = average;
        this.streak = streak;
    }

    public static HomeStats load() {
        try {
            DuelHistory history = DuelHistory.load();
            int best = -1;
            for (DuelHistory.Rank r : history.ranks) {
                if (r.placed && r.division > best) best = r.division;
            }
            String rank = best >= 0 ? Lang.tr(PvpRank.name(best)) : Lang.tr("Non classé");
            String average = history.scored > 0 ? String.valueOf(Math.round(history.average)) : "-";
            // Du plus récent au plus ancien : on compte les victoires jusqu'au premier autre résultat.
            int wins = 0;
            for (DuelHistory.Duel d : history.duels) {
                if (d.result() != WIN) break;
                wins++;
            }
            return new HomeStats(rank, average, String.valueOf(wins));
        } catch (Throwable t) {
            LauncherLog.err("[HomeStats] historique des duels illisible : " + t);
            return new HomeStats(Lang.tr("Non classé"), "-", "0");
        }
    }

    /** Valeur de {@code NativeCombat.REPORT_RESULT} pour un duel gagné. */
    private static final int WIN = 1;
}
