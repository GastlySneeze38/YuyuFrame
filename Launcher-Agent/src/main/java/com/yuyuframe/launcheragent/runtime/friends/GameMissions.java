package com.yuyuframe.launcheragent.runtime.friends;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Les missions du jour et de la semaine, vues du jeu (onglet Missions du menu
 * YuyuFrame).
 *
 * <p>Ce sont celles de l'écran Missions du launcher : il les lit sur
 * LauncherAPI ({@code GET /v1/pass}) et les rend par la question
 * {@code missions} du canal du jeu ({@code Backend/src/play/game_link.rs}).
 * Même mécanique que {@link GameFriends} : rien ne bloque.
 */
public final class GameMissions {

    private GameMissions() {
    }

    /** Une mission. */
    public static final class Mission {
        /** {@code daily} ou {@code weekly}. */
        public final String period;
        /** {@code winDuels}, {@code playDuels}, {@code scoreAbove}, {@code scoresAbove}, {@code streak}, {@code quickWin},
         *  {@code longWin}, {@code foes}, {@code servers}, {@code bothModes}, {@code rrGain} (LauncherAPI {@code launcher/missions/}). */
        public final String kind;
        /** {@code target} : la valeur de la phrase (note, secondes, RR) ; {@code goal} : le nombre à atteindre. */
        /** {@code mace}, {@code sword}, ou vide pour tous les modes. */
        public final String mode;
        public final long target, progress, goal;
        /** Fin de la mission, en millisecondes. */
        public final long endsAt;
        public final long xp;

        Mission(String[] p, int i) {
            this.period = p[i];
            this.kind = p[i + 1];
            this.mode = p[i + 2];
            this.target = num(p[i + 3]);
            this.progress = num(p[i + 4]);
            this.goal = Math.max(1L, num(p[i + 5]));
            this.endsAt = num(p[i + 6]);
            this.xp = num(p[i + 7]);
        }

        public boolean done() {
            return progress >= goal;
        }

        private static long num(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    private static final int FIELDS = 8;
    /** Redemandées au plus toutes les 30 s tant que l'onglet est affiché. */
    private static final long REFRESH_MS = 30_000L;

    private static volatile List<Mission> missions = Collections.emptyList();
    private static volatile boolean loaded;
    private static volatile String error;
    private static long request;
    private static long lastRequestAt;

    public static boolean available() {
        return NativeLink.available();
    }

    public static List<Mission> missions() {
        return missions;
    }

    public static boolean loaded() {
        return loaded;
    }

    public static String error() {
        return error;
    }

    /** À appeler tant que l'onglet est affiché. */
    public static void keepFresh() {
        if (request != 0L || !available()) return;
        long now = System.currentTimeMillis();
        if ((loaded || error != null) && now - lastRequestAt < REFRESH_MS) return;
        lastRequestAt = now;
        request = NativeLink.send("missions");
        if (request == 0L) error = Lang.tr("Le launcher YuyuFrame n'est plus joignable");
    }

    /** À chaque image, sur le fil du jeu. */
    public static void tick() {
        if (request == 0L) return;
        String reply = NativeLink.poll(request);
        if (reply == NativeLink.PENDING) return;
        request = 0L;
        String[] parts = reply.split("\t", -1);
        if (parts.length < 1 || !"missions".equals(parts[0]) || (parts.length - 1) % FIELDS != 0) {
            error = parts.length >= 2 && "error".equals(parts[0]) && !parts[1].isEmpty()
                ? parts[1] : Lang.tr("Réponse du launcher illisible");
            return;
        }
        List<Mission> list = new ArrayList<>();
        for (int i = 1; i + FIELDS - 1 < parts.length; i += FIELDS) list.add(new Mission(parts, i));
        missions = Collections.unmodifiableList(list);
        error = null;
        loaded = true;
    }
}
