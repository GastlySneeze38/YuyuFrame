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
        /**
         * Ce que le duel a rapporté au classement, {@code null} s'il n'y compte
         * pas : total, base, jeu, adversaire (multiplicateur), répétition
         * (même adversaire le même jour), division puis points après le duel.
         * Voir {@code content-core/src/combat/ranked.rs}.
         */
        public final double[] ranked;

        Duel(long time, String opponent, double[] report, double[] ranked) {
            this.time = time;
            this.opponent = opponent;
            this.report = report;
            this.ranked = ranked;
        }

        /** Note sur 500, ou −1 si le duel n'est pas noté. */
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

    /** Où en est le joueur dans le classement d'un mode (docs/scoring/classement.md). */
    public static final class Rank {
        /** 1 masse, 2 épée — voir {@link NativeCombat#REPORT_MODE}. */
        public final int mode;
        /** De 0 (Bois 1) à 18 (Champion) : trois divisions par rang. */
        public final int division;
        /** Dans la division, sur 500 (sans plafond en Champion). */
        public final int points;
        /** Duels classés joués dans ce mode. */
        public final int duels;
        /** Les dix duels de placement sont joués : le rang s'affiche. */
        public final boolean placed;

        Rank(int mode, int division, int points, int duels, boolean placed) {
            this.mode = mode;
            this.division = division;
            this.points = points;
            this.duels = duels;
            this.placed = placed;
        }
    }

    /** Moyenne des notes, ou −1 s'il n'y a aucun duel noté. */
    public final double average;
    /** Nombre de duels notés : ce sur quoi la moyenne repose. */
    public final int scored;
    /** Du plus récent au plus ancien. */
    public final List<Duel> duels;
    /** Un par mode classé (masse, épée). */
    public final List<Rank> ranks;

    private DuelHistory(double average, int scored, List<Duel> duels, List<Rank> ranks) {
        this.average = average;
        this.scored = scored;
        this.duels = duels;
        this.ranks = ranks;
    }

    /** Le classement d'un mode, {@code null} s'il n'en a pas. */
    public Rank rank(int mode) {
        for (Rank rank : ranks) {
            if (rank.mode == mode) return rank;
        }
        return null;
    }

    /** L'historique de l'instance courante ; vide si le noyau ne répond pas. */
    public static DuelHistory load() {
        return parse(NativeCombat.history(System.getProperty("user.dir", "")));
    }

    static DuelHistory parse(String text) {
        double average = -1;
        int scored = 0;
        List<Duel> duels = new ArrayList<>();
        List<Rank> ranks = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length >= 3 && "avg".equals(fields[0])) {
                        average = Double.parseDouble(fields[1]);
                        scored = Integer.parseInt(fields[2]);
                    } else if (fields.length >= 6 && "rank".equals(fields[0])) {
                        ranks.add(new Rank((int) Double.parseDouble(fields[1]), Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), "1".equals(fields[5])));
                    } else if (fields.length >= 4 && "duel".equals(fields[0])) {
                        String[] numbers = fields[3].split(",");
                        if (numbers.length < NativeCombat.REPORT_LEN) {
                            continue;
                        }
                        double[] report = new double[NativeCombat.REPORT_LEN];
                        for (int i = 0; i < report.length; i++) {
                            report[i] = Double.parseDouble(numbers[i]);
                        }
                        double[] ranked = null;
                        if (fields.length >= 5 && !fields[4].isEmpty()) {
                            String[] parts = fields[4].split(",");
                            ranked = new double[parts.length];
                            for (int i = 0; i < parts.length; i++) {
                                ranked[i] = Double.parseDouble(parts[i]);
                            }
                        }
                        duels.add(new Duel(Long.parseLong(fields[1]), fields[2], report, ranked));
                    }
                } catch (NumberFormatException ignored) {
                    // Une ligne illisible est sautée : le reste de l'historique reste affichable.
                }
            }
        }
        return new DuelHistory(average, scored, duels, ranks);
    }
}
