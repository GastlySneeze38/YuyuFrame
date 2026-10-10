package com.yuyuframe.launcheragent.runtime.account;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Les comptes Minecraft du launcher, vus du jeu : leur liste, et le
 * changement de compte sans relancer.
 *
 * <p>Les comptes vivent dans le launcher, qui garde leurs jetons de
 * rafraîchissement. Le jeu lui demande la liste (question {@code accounts}),
 * puis, pour changer, un jeton frais du compte choisi ({@code switch}) — par
 * le canal du jeu ({@link NativeLink}). Le compte est alors remplacé dans le
 * jeu par la liaison de la version ({@link AccessPoint#CLIENT_SET_ACCOUNT}).
 *
 * <p>Rien ne bloque : une question est déposée, {@link #tick()} relève la
 * réponse à une image suivante. Le remplacement lui-même part sur un fil à
 * part — sur certaines versions il interroge le réseau.
 *
 * <p>Changer n'a de sens que hors d'un monde : une fois connecté à un
 * serveur, l'identité est liée à la connexion. C'est à l'appelant de ne pas
 * le proposer en jeu : la liste vit sur l'écran-titre (voir
 * {@code runtime.ui.ingameui.TitleAccountBar}).
 */
public final class GameAccounts {

    private GameAccounts() {
    }

    /** Un compte du launcher. */
    public static final class Account {
        public final String uuid;
        public final String name;
        /** Compte hors ligne : pas de jeton, ne rejoint que les serveurs qui ne vérifient pas les comptes. */
        public final boolean offline;

        Account(String uuid, String name, boolean offline) {
            this.uuid = uuid;
            this.name = name;
            this.offline = offline;
        }
    }

    private static volatile List<Account> accounts = Collections.emptyList();
    /** Le compte avec lequel le jeu joue, tel que le launcher le connaît ; {@code null} tant qu'on ne l'a pas demandé. */
    private static volatile String currentUuid;
    /** Dernière erreur à montrer au joueur, ou {@code null}. */
    private static volatile String error;
    private static volatile boolean switching;

    private static long listRequest;
    private static long switchRequest;

    /** Le jeu a-t-il un launcher à qui parler ? Sinon il n'y a ni liste ni changement possible. */
    public static boolean available() {
        return NativeLink.available();
    }

    public static List<Account> accounts() {
        return accounts;
    }

    public static boolean isCurrent(Account account) {
        return account != null && account.uuid.equals(currentUuid);
    }

    /** Une question attend-elle encore sa réponse ? */
    public static boolean loading() {
        return listRequest != 0L;
    }

    public static boolean switching() {
        return switching;
    }

    public static String error() {
        return error;
    }

    /** Redemande la liste au launcher. Sans effet si une demande est déjà partie. */
    public static void refresh() {
        if (listRequest != 0L || !available()) {
            return;
        }
        error = null;
        listRequest = NativeLink.send("accounts");
        if (listRequest == 0L) {
            error = "Le launcher YuyuFrame n'est plus joignable";
        }
    }

    /** Demande à passer sur ce compte. Le résultat arrive plus tard : voir {@link #switching()} et {@link #error()}. */
    public static void switchTo(Account account) {
        if (account == null || switching || !available()) {
            return;
        }
        error = null;
        switchRequest = NativeLink.send("switch\t" + account.uuid);
        if (switchRequest == 0L) {
            error = "Le launcher YuyuFrame n'est plus joignable";
            return;
        }
        switching = true;
    }

    /** À chaque image, sur le fil du jeu : relève les réponses du launcher. Ne coûte rien quand rien n'est attendu. */
    public static void tick() {
        if (listRequest != 0L) {
            String reply = NativeLink.poll(listRequest);
            if (reply != NativeLink.PENDING) {
                listRequest = 0L;
                readList(reply);
            }
        }
        if (switchRequest != 0L) {
            String reply = NativeLink.poll(switchRequest);
            if (reply != NativeLink.PENDING) {
                switchRequest = 0L;
                readSwitch(reply);
            }
        }
    }

    /** {@code accounts⇥<uuid en jeu>⇥(<uuid>⇥<pseudo>⇥<hors ligne 0/1>)…} */
    private static void readList(String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 2 || !"accounts".equals(parts[0])) {
            error = failure(parts);
            return;
        }
        List<Account> list = new ArrayList<>();
        for (int i = 2; i + 2 < parts.length; i += 3) {
            list.add(new Account(parts[i], parts[i + 1], "1".equals(parts[i + 2])));
        }
        accounts = Collections.unmodifiableList(list);
        currentUuid = "-".equals(parts[1]) ? null : parts[1];
    }

    /** {@code account⇥<uuid>⇥<pseudo>⇥<hors ligne 0/1>⇥<jeton Minecraft>} */
    private static void readSwitch(String reply) {
        String[] parts = reply.split("\t", -1);
        if (parts.length < 5 || !"account".equals(parts[0])) {
            error = failure(parts);
            switching = false;
            return;
        }
        UUID id = parseUuid(parts[1]);
        if (id == null) {
            error = "Compte illisible";
            switching = false;
            return;
        }
        String uuid = parts[1];
        String name = parts[2];
        boolean offline = "1".equals(parts[3]);
        String token = parts[4];
        Thread worker = new Thread(() -> {
            try {
                Object result = AccessorRegistry.invoke(AccessPoint.CLIENT_SET_ACCOUNT, null, name, id, token, offline);
                if (result == null) {
                    currentUuid = uuid;
                    // Le skin d'un compte hors ligne est celui du compte : il change avec lui.
                    com.yuyuframe.launcheragent.runtime.skin.OwnSkin.refresh();
                    LauncherLog.info("[ACCOUNT] Compte Minecraft changé : " + name);
                } else {
                    error = "Changement de compte impossible";
                    LauncherLog.err("[ACCOUNT] Changement de compte refusé : " + result);
                }
            } catch (Throwable failure) {
                error = "Changement de compte impossible";
                LauncherLog.err("[ACCOUNT] Changement de compte en échec", failure);
            } finally {
                switching = false;
            }
        }, "yf-account");
        worker.setDaemon(true);
        worker.start();
    }

    /** Le message d'une réponse {@code error⇥…}, ou un message générique pour toute autre ligne inattendue. */
    private static String failure(String[] parts) {
        if (parts.length >= 2 && "error".equals(parts[0]) && !parts[1].isEmpty()) {
            return parts[1];
        }
        return "Réponse du launcher illisible";
    }

    /** Le launcher écrit l'UUID avec ou sans tirets selon l'origine du compte. */
    private static UUID parseUuid(String text) {
        try {
            String s = text.trim();
            if (s.length() == 32) {
                s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                    + s.substring(16, 20) + "-" + s.substring(20);
            }
            return UUID.fromString(s);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
