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

        /** Ce qui reconnaît une mission d'une lecture à l'autre (la ligne n'a pas d'identifiant). */
        String key() {
            return period + '|' + kind + '|' + mode + '|' + target + '|' + goal + '|' + endsAt;
        }

        /** La phrase de la mission, comme dans le launcher (« Gagne 3 duels »). */
        public String text() {
            String key;
            switch (kind) {
                case "winDuels": key = "Gagne {n} duels"; break;
                case "playDuels": key = "Joue {n} duels classés"; break;
                case "scoreAbove": key = "Fais une note de {v} ou plus"; break;
                case "scoresAbove": key = "Fais {n} duels à {v} ou plus"; break;
                case "streak": key = "Enchaîne {n} victoires"; break;
                case "quickWin": key = "Gagne un duel en moins de {v} s"; break;
                case "longWin": key = "Gagne un duel de plus de {v} s"; break;
                case "foes": key = "Bats {n} adversaires différents"; break;
                case "servers": key = "Joue sur {n} serveurs différents"; break;
                case "bothModes": key = "Gagne à la masse et à l'épée"; break;
                case "rrGain": key = "Gagne {v} RR"; break;
                default: return kind;
            }
            return Lang.tr(key).replace("{n}", String.valueOf(goal)).replace("{v}", String.valueOf(target));
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
        send(now);
    }

    private static void send(long now) {
        lastRequestAt = now;
        request = NativeLink.send("missions");
        if (request == 0L) error = Lang.tr("Le launcher YuyuFrame n'est plus joignable");
    }

    // ── Les missions finies, pour la bannière de fin de duel ──
    //
    // Une mission passe à « faite » côté serveur quand il a compté le duel.
    // On compare donc deux lectures : celle d'avant le duel (relue à son
    // ouverture si elle est vieille), et celles d'après (dès que le serveur a
    // vérifié le duel, puis un peu plus tard en filet). Une mission faite dans
    // la seconde et pas dans la première est annoncée une fois.

    /** Les missions tout juste finies, pas encore annoncées. */
    private static final java.util.ArrayDeque<Mission> completed = new java.util.ArrayDeque<>();
    /** Relectures prévues (ms), après un duel. */
    private static final java.util.List<Long> planned = new ArrayList<>();
    /** Annoncées depuis le lancement : une mission ne l'est jamais deux fois. */
    private static final java.util.Set<String> announced = new java.util.HashSet<>();

    /** Une lecture de référence, si la dernière a plus de {@code maxAgeMs} : à l'ouverture d'un duel. */
    public static void refreshIfOlderThan(long maxAgeMs) {
        if (request != 0L || !available()) return;
        long now = System.currentTimeMillis();
        if (loaded && now - lastRequestAt < maxAgeMs) return;
        send(now);
    }

    /** Relire dans {@code delayMs} (0 : dès que possible), même si la dernière lecture est récente. */
    public static void refreshIn(long delayMs) {
        planned.add(System.currentTimeMillis() + Math.max(0L, delayMs));
    }

    /** Une mission du jour finie, pour l'exemple de l'éditeur de HUD. */
    public static Mission sample() {
        return new Mission(new String[]{ "daily", "winDuels", "", "0", "3", "3", "0", "250" }, 0);
    }

    /** La prochaine mission finie à annoncer, ou {@code null}. */
    public static Mission takeCompleted() {
        return completed.poll();
    }

    /** À chaque image, sur le fil du jeu. */
    public static void tick() {
        if (request == 0L && !planned.isEmpty() && available()) {
            long now = System.currentTimeMillis();
            if (planned.removeIf(at -> at <= now)) send(now);
        }
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
        // Faite maintenant, pas faite à la lecture d'avant : tout juste finie.
        // Une mission absente d'avant (nouvelle journée, relance) ne compte
        // pas — on ne sait pas si elle vient d'être finie.
        if (loaded) {
            java.util.Set<String> wasOpen = new java.util.HashSet<>();
            for (Mission m : missions) if (!m.done()) wasOpen.add(m.key());
            for (Mission m : list) {
                if (m.done() && wasOpen.contains(m.key()) && announced.add(m.key())) completed.add(m);
            }
        }
        missions = Collections.unmodifiableList(list);
        error = null;
        loaded = true;
    }
}
