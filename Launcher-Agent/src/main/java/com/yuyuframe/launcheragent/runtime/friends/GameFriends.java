package com.yuyuframe.launcheragent.runtime.friends;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Les amis YuyuFrame, vus du jeu : la liste et l'invitation.
 *
 * <p>Tout passe par le launcher, qui parle au serveur avec le compte
 * YuyuFrame (questions {@code friends2} et {@code invite} du canal du jeu,
 * {@code Backend/src/play/game_link.rs}) — le jeu n'a jamais le jeton. La
 * réponse arrive triée comme le panneau du launcher : en jeu, en ligne, puis
 * hors ligne (les plus récemment vus d'abord).
 *
 * <p>Même mécanique que {@code GameAccounts} : rien ne bloque, une question
 * est déposée et {@link #tick()} relève la réponse à une image suivante.
 */
public final class GameFriends {

    private GameFriends() {
    }

    /** Un ami, tel que le launcher le décrit. Les champs absents sont des chaînes vides. */
    public static final class Friend {
        /** Identifiant public (UUID), seul à repartir dans une invitation. */
        public final String id;
        public final String name;
        /** {@code online}, {@code away}, {@code dnd} ou {@code offline}. */
        public final String state;
        public final boolean inGame;
        /** Compte Minecraft principal, pour la tête ; vide sans compte prouvé. */
        public final String minecraftUuid;
        /** Statut personnalisé encore valable. */
        public final String custom;
        /** {@code game}, {@code launcher} ou vide. */
        public final String activity;
        public final String version;
        public final String loader;
        /** {@code ranked:<mode>} ou {@code casual:<mode>}, vide hors duel. */
        public final String duel;
        /** « hôte:port », seulement si l'ami le montre. */
        public final String server;
        /** Hors ligne : dernière présence en secondes Unix, 0 si inconnue. */
        public final long lastSeen;

        Friend(String[] p, int i) {
            this.id = p[i];
            this.name = p[i + 1];
            this.state = p[i + 2];
            this.inGame = "1".equals(p[i + 3]);
            this.minecraftUuid = p[i + 4];
            this.custom = drawable(p[i + 5]);
            this.activity = p[i + 6];
            this.version = p[i + 7];
            this.loader = p[i + 8];
            this.duel = p[i + 9];
            this.server = p[i + 10];
            long seen = 0L;
            try {
                if (!p[i + 11].isEmpty()) seen = Long.parseLong(p[i + 11]);
            } catch (NumberFormatException ignored) {
                // Champ illisible : « vu il y a… » n'est simplement pas affiché.
            }
            this.lastSeen = seen;
        }

        public boolean online() {
            return !"offline".equals(state);
        }
    }

    /**
     * Le texte sans ce que la police du jeu ne sait pas dessiner : les
     * émojis (hors du plan de base, ou symboles), leurs sélecteurs de
     * variante et leurs jointures. Un statut « En vacances 🏖️ » s'affichait
     * « En vacances ??? ».
     */
    static String drawable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            boolean emoji = cp > 0xFFFF
                || cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D
                || Character.getType(cp) == Character.OTHER_SYMBOL;
            if (!emoji) out.appendCodePoint(cp);
        });
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** Champs par ami dans la réponse {@code friends2}. */
    private static final int FIELDS = 12;
    /** La liste est redemandée toutes les 2 s tant qu'elle est affichée : le
     *  launcher la sert de sa mémoire, et ne la recharge au serveur que quand
     *  un ami a changé (flux temps réel). */
    private static final long REFRESH_MS = 2_000L;
    /** Durée d'affichage du résultat d'une invitation. */
    private static final long NOTICE_MS = 4_000L;

    private static volatile List<Friend> friends = Collections.emptyList();
    private static volatile boolean loaded;
    private static volatile String error;
    private static volatile String notice;
    private static volatile boolean noticeIsError;
    private static volatile long noticeUntil;

    private static long listRequest;
    private static long lastRequestAt;
    private static long inviteRequest;
    private static String invitedName;

    /** Le jeu a-t-il un launcher à qui parler ? */
    public static boolean available() {
        return NativeLink.available();
    }

    public static List<Friend> friends() {
        return friends;
    }

    /** Vrai une fois qu'une liste (même vide) a été reçue. */
    public static boolean loaded() {
        return loaded;
    }

    public static String error() {
        return error;
    }

    /** Le résultat de la dernière invitation, tant qu'il est à montrer ; sinon {@code null}. */
    public static String notice() {
        return System.currentTimeMillis() < noticeUntil ? notice : null;
    }

    public static boolean noticeIsError() {
        return noticeIsError;
    }

    public static boolean inviting() {
        return inviteRequest != 0L;
    }

    /**
     * À appeler tant que la liste est affichée : la demande au launcher si
     * elle est absente ou vieille de plus de {@link #REFRESH_MS}.
     */
    public static void keepFresh() {
        if (listRequest != 0L || !available()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (loaded && now - lastRequestAt < REFRESH_MS) {
            return;
        }
        lastRequestAt = now;
        listRequest = NativeLink.send("friends2");
        if (listRequest == 0L) {
            error = Lang.tr("Le launcher YuyuFrame n'est plus joignable");
        }
    }

    /** Invite cet ami sur le serveur où l'on joue. Le résultat arrive par {@link #notice()}. */
    public static void invite(Friend friend) {
        if (friend == null || inviteRequest != 0L || !available()) {
            return;
        }
        invitedName = friend.name;
        inviteRequest = NativeLink.send("invite\t" + friend.id);
        if (inviteRequest == 0L) {
            show(Lang.tr("Le launcher YuyuFrame n'est plus joignable"), true);
        }
    }

    /** À chaque image, sur le fil du jeu. Ne coûte rien quand rien n'est attendu. */
    public static void tick() {
        if (listRequest != 0L) {
            String reply = NativeLink.poll(listRequest);
            if (reply != NativeLink.PENDING) {
                listRequest = 0L;
                readList(reply);
            }
        }
        if (inviteRequest != 0L) {
            String reply = NativeLink.poll(inviteRequest);
            if (reply != NativeLink.PENDING) {
                inviteRequest = 0L;
                if ("ok".equals(reply)) {
                    show(Lang.tr("Invitation envoyée à") + " " + invitedName, false);
                } else {
                    show(failure(reply.split("\t", -1)), true);
                }
            }
        }
    }

    /** {@code friends2⇥(douze champs)…} — voir {@code friends_line_v2} côté launcher. */
    private static void readList(String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 1 || !"friends2".equals(parts[0]) || (parts.length - 1) % FIELDS != 0) {
            error = failure(parts);
            return;
        }
        List<Friend> list = new ArrayList<>();
        for (int i = 1; i + FIELDS - 1 < parts.length; i += FIELDS) {
            list.add(new Friend(parts, i));
        }
        friends = Collections.unmodifiableList(list);
        error = null;
        loaded = true;
    }

    private static void show(String text, boolean isError) {
        notice = text;
        noticeIsError = isError;
        noticeUntil = System.currentTimeMillis() + NOTICE_MS;
    }

    /** Le message d'une réponse {@code error⇥…}, ou un message générique pour toute autre ligne inattendue. */
    private static String failure(String[] parts) {
        if (parts.length >= 2 && "error".equals(parts[0]) && !parts[1].isEmpty()) {
            return parts[1];
        }
        return Lang.tr("Réponse du launcher illisible");
    }
}
