package com.yuyuframe.launcheragent.runtime.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * L'historique des duels, tel que le noyau natif le rend
 * ({@link NativeCombat#history}) et tel que les écrans du module le lisent.
 *
 * <p>Rien n'est calculé ici : la moyenne, les notes et le tri viennent du
 * noyau. Cette classe ne fait que découper le texte reçu.
 */
public final class DuelHistory {

    /** Un duel terminé. */
    public static final class Duel {
        /** Heure d'ouverture, en millisecondes depuis 1970 ; 0 si inconnue. */
        public final long time;
        /** Pseudo de l'adversaire ; vide s'il n'est pas connu. */
        public final String opponent;
        /** Le résumé du duel — constantes {@code REPORT_*} de {@link NativeCombat}. */
        public final double[] report;

        Duel(long time, String opponent, double[] report) {
            this.time = time;
            this.opponent = opponent;
            this.report = report;
        }

        /** Note sur 100, ou −1 si le duel n'est pas noté. */
        public double score() {
            return report[NativeCombat.REPORT_SCORE];
        }

        /** 1 victoire, 2 défaite, 3 nul. */
        public int result() {
            return (int) report[NativeCombat.REPORT_RESULT];
        }

        /** 1 masse, 2 épée, 3 autre — voir {@link NativeCombat#REPORT_MODE}. */
        public int mode() {
            return (int) report[NativeCombat.REPORT_MODE];
        }
    }

    /** Moyenne des notes, ou −1 s'il n'y a aucun duel noté. */
    public final double average;
    /** Nombre de duels notés : ce sur quoi la moyenne repose. */
    public final int scored;
    /** Du plus récent au plus ancien. */
    public final List<Duel> duels;

    private DuelHistory(double average, int scored, List<Duel> duels) {
        this.average = average;
        this.scored = scored;
        this.duels = duels;
    }

    /** L'historique de l'instance courante ; vide si le noyau ne répond pas. */
    public static DuelHistory load() {
        return parse(NativeCombat.history(System.getProperty("user.dir", "")));
    }

    static DuelHistory parse(String text) {
        double average = -1;
        int scored = 0;
        List<Duel> duels = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length >= 3 && "avg".equals(fields[0])) {
                        average = Double.parseDouble(fields[1]);
                        scored = Integer.parseInt(fields[2]);
                    } else if (fields.length >= 4 && "duel".equals(fields[0])) {
                        String[] numbers = fields[3].split(",");
                        if (numbers.length < NativeCombat.REPORT_LEN) {
                            continue;
                        }
                        double[] report = new double[NativeCombat.REPORT_LEN];
                        for (int i = 0; i < report.length; i++) {
                            report[i] = Double.parseDouble(numbers[i]);
                        }
                        duels.add(new Duel(Long.parseLong(fields[1]), fields[2], report));
                    }
                } catch (NumberFormatException ignored) {
                    // Une ligne illisible est sautée : le reste de l'historique reste affichable.
                }
            }
        }
        return new DuelHistory(average, scored, duels);
    }
}
