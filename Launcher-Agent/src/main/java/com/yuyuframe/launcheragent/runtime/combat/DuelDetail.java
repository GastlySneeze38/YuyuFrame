package com.yuyuframe.launcheragent.runtime.combat;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Le détail des familles d'un duel, tel que le noyau natif le rend
 * ({@link NativeCombat#detail}) : pour chaque famille, des faits qui l'ont
 * fait monter ou baisser, des moments datés, et la même mesure chez
 * l'adversaire quand elle existe de son côté. C'est ce que montre la fenêtre
 * qui s'ouvre au clic sur une famille.
 *
 * <p>Rien n'est calculé ici : cette classe ne fait que découper le texte reçu
 * (format dans {@code content-core/src/combat/score/mod.rs}, {@code detail_text}).
 * Les codes sont traduits par l'écran.
 */
public final class DuelDetail {

    /** Un fait : il a fait monter ({@code up}) ou baisser la famille ; {@code weight} dit combien. */
    public static final class Fact {
        public final int family;
        public final String code;
        public final boolean up;
        public final double weight;
        public final double[] values;

        Fact(int family, String code, boolean up, double weight, double[] values) {
            this.family = family;
            this.code = code;
            this.up = up;
            this.weight = weight;
            this.values = values;
        }
    }

    /** Un moment du duel, en secondes depuis son ouverture. */
    public static final class Moment {
        public final int family;
        public final double seconds;
        public final String code;
        public final boolean good;
        public final double weight;
        public final double[] values;

        Moment(int family, double seconds, String code, boolean good, double weight, double[] values) {
            this.family = family;
            this.seconds = seconds;
            this.code = code;
            this.good = good;
            this.weight = weight;
            this.values = values;
        }
    }

    /** La même mesure chez nous et chez lui. */
    public static final class Versus {
        public final int family;
        public final String code;
        public final double[] mine;
        public final double[] theirs;

        Versus(int family, String code, double[] mine, double[] theirs) {
            this.family = family;
            this.code = code;
            this.mine = mine;
            this.theirs = theirs;
        }
    }

    /**
     * Une action jugée et ce qu'elle a rapporté à la note sur 500 (ABI 14) :
     * ce qu'elle a donné ({@code value}, de 0 à 1) face à ce qu'en tire un
     * joueur moyen ({@code expected}). Les points de toutes les actions d'une
     * famille font ceux de la famille. {@code seconds} vaut −1 pour une
     * tendance tenue sur tout le duel (le temps passé au sol sous lui…).
     */
    public static final class Action {
        public final int family;
        public final double seconds;
        public final String code;
        public final double points;
        public final double value;
        public final double expected;

        Action(int family, double seconds, String code, double points, double value, double expected) {
            this.family = family;
            this.seconds = seconds;
            this.code = code;
            this.points = points;
            this.value = value;
            this.expected = expected;
        }

        /** Une tendance sur tout le duel, sans instant précis. */
        public boolean isTrend() {
            return seconds < 0;
        }
    }

    /** Un détail vide : le noyau absent, ou le journal introuvable. */
    public static final DuelDetail EMPTY = new DuelDetail(Collections.<Fact>emptyList(), Collections.<Moment>emptyList(),
        Collections.<Versus>emptyList(), Collections.<Action>emptyList());

    public final List<Fact> facts;
    public final List<Moment> moments;
    public final List<Versus> versus;
    public final List<Action> actions;

    private DuelDetail(List<Fact> facts, List<Moment> moments, List<Versus> versus, List<Action> actions) {
        this.facts = facts;
        this.moments = moments;
        this.versus = versus;
        this.actions = actions;
    }

    /** Les actions d'une famille (toutes si {@code family} vaut −1), celles qui ont le plus pesé d'abord. */
    public List<Action> actions(int family) {
        List<Action> out = new ArrayList<Action>();
        for (Action a : actions) {
            if (family < 0 || a.family == family) out.add(a);
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.points), Math.abs(a.points)));
        return out;
    }

    /** La somme des points d'une famille : ce qu'elle a fait gagner ou perdre à la note. */
    public double points(int family) {
        double sum = 0;
        for (Action a : actions) {
            if (a.family == family) sum += a.points;
        }
        return sum;
    }

    /** Le détail du duel ouvert à {@code time}, dans l'instance en cours. Relit un journal : à l'ouverture d'une page. */
    public static DuelDetail load(long time) {
        String text = NativeCombat.detail(System.getProperty("user.dir", ""), time);
        return text == null ? EMPTY : parse(text);
    }

    /** Les faits d'une famille, du plus fort au plus faible. */
    public List<Fact> facts(int family, boolean up) {
        List<Fact> out = new ArrayList<Fact>();
        for (Fact f : facts) {
            if (f.family == family && f.up == up) out.add(f);
        }
        out.sort((a, b) -> Double.compare(b.weight, a.weight));
        return out;
    }

    /** Les moments d'une famille, les plus parlants d'abord. */
    public List<Moment> moments(int family) {
        List<Moment> out = new ArrayList<Moment>();
        for (Moment m : moments) {
            if (m.family == family) out.add(m);
        }
        out.sort((a, b) -> Double.compare(b.weight, a.weight));
        return out;
    }

    /** La mesure face à face d'une famille, ou {@code null} si elle n'existe pas de son côté. */
    public Versus versus(int family) {
        for (Versus v : versus) {
            if (v.family == family) return v;
        }
        return null;
    }

    static DuelDetail parse(String text) {
        List<Fact> facts = new ArrayList<Fact>();
        List<Moment> moments = new ArrayList<Moment>();
        List<Versus> versus = new ArrayList<Versus>();
        List<Action> actions = new ArrayList<Action>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            try {
                if (f.length == 7 && "action".equals(f[0])) {
                    actions.add(new Action(Integer.parseInt(f[1]), Double.parseDouble(f[2]), f[3], Double.parseDouble(f[4]),
                        Double.parseDouble(f[5]), Double.parseDouble(f[6])));
                } else if (f.length == 6 && "fact".equals(f[0])) {
                    facts.add(new Fact(Integer.parseInt(f[1]), f[2], "1".equals(f[3]), Double.parseDouble(f[4]), numbers(f[5])));
                } else if (f.length == 7 && "moment".equals(f[0])) {
                    moments.add(new Moment(Integer.parseInt(f[1]), Double.parseDouble(f[2]), f[3], "1".equals(f[4]),
                        Double.parseDouble(f[5]), numbers(f[6])));
                } else if (f.length == 5 && "versus".equals(f[0])) {
                    versus.add(new Versus(Integer.parseInt(f[1]), f[2], numbers(f[3]), numbers(f[4])));
                }
            } catch (NumberFormatException error) {
                // Une ligne illisible est sautée ; le reste du détail reste affichable.
                LauncherLog.warn("[COMBAT] Ligne de détail illisible : " + line + " (" + error.getMessage() + ")");
            }
        }
        return new DuelDetail(facts, moments, versus, actions);
    }

    private static double[] numbers(String field) {
        if (field.isEmpty()) return new double[0];
        String[] parts = field.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }
}
