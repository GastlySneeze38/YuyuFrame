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
 * YuyuFrame (questions {@code friends} et {@code invite} du canal du jeu,
 * {@code Backend/src/play/game_link.rs}) — le jeu n'a jamais le jeton. La
 * réponse arrive triée : en jeu, puis en ligne, puis hors ligne, par pseudo.
 *
 * <p>Même mécanique que {@code GameAccounts} : rien ne bloque, une question
 * est déposée et {@link #tick()} relève la réponse à une image suivante.
 */
public final class GameFriends {

    private GameFriends() {
    }

    /** Un ami, tel que le launcher le décrit. */
    public static final class Friend {
        /** Identifiant public (UUID), seul à repartir dans une invitation. */
        public final String id;
        public final String name;
        /** {@code online}, {@code away}, {@code dnd} ou {@code offline}. */
        public final String state;
        public final boolean inGame;

        Friend(String id, String name, String state, boolean inGame) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.inGame = inGame;
        }

        public boolean online() {
            return !"offline".equals(state);
        }
    }

    /** La liste est redemandée au plus toutes les 20 s tant qu'elle est affichée. */
    private static final long REFRESH_MS = 20_000L;
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
        listRequest = NativeLink.send("friends");
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

    /** {@code friends⇥(<id>⇥<pseudo>⇥<état>⇥<en jeu 0/1>)…} */
    private static void readList(String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 1 || !"friends".equals(parts[0]) || (parts.length - 1) % 4 != 0) {
            error = failure(parts);
            return;
        }
        List<Friend> list = new ArrayList<>();
        for (int i = 1; i + 3 < parts.length; i += 4) {
            list.add(new Friend(parts[i], parts[i + 1], parts[i + 2], "1".equals(parts[i + 3])));
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
