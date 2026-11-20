package com.yuyuframe.launcheragent.runtime.friends;

import com.yuyuframe.launcheragent.runtime.ipc.NativeLink;

/**
 * Mon profil YuyuFrame, pour la carte de joueur de l'accueil : la bannière,
 * la couleur et le titre choisis dans le launcher.
 *
 * <p>Question {@code profile} du canal ; le launcher répond
 * {@code profile⇥<bannière>⇥<couleur #rrggbb>⇥<titre>} (voir
 * {@code play/game_link.rs::profile_line}). Un launcher d'avant cette question
 * répond « question inconnue » : la carte garde alors sa bannière par défaut,
 * sans titre — rien n'est cassé.
 *
 * <p>« Personnaliser » ne modifie rien ici : le launcher revient devant,
 * ouvert sur le profil ({@code open⇥profile}). Le profil est relu une minute
 * après, le temps d'y changer quelque chose.
 */
public final class GameProfile {
    private GameProfile() {}

    /** Relu au plus toutes les 60 s tant que l'accueil est affiché. */
    private static final long REFRESH_MS = 60_000L;

    private static volatile String banner = "";
    private static volatile String accent = "";
    private static volatile String title = "";
    private static volatile boolean loaded;
    private static long request;
    private static long openRequest;
    private static long lastRequestAt;

    /** Code de la bannière ({@code aurora}…), vide = aucune. */
    public static String banner() { return banner; }

    /** Couleur choisie {@code #rrggbb}, vide = aucune. */
    public static String accent() { return accent; }

    /** Libellé du titre mis en vitrine, vide = aucun. */
    public static String title() { return title; }

    public static boolean loaded() { return loaded; }

    /** À chaque image où la carte est dessinée : redemande quand c'est vieux. */
    public static void keepFresh() {
        long now = System.currentTimeMillis();
        if (request != 0L || !NativeLink.available()) return;
        if (loaded && now - lastRequestAt < REFRESH_MS) return;
        lastRequestAt = now;
        request = NativeLink.send("profile");
    }

    /** Le launcher revient devant, sur le profil ; relu peu après. */
    public static void openInLauncher() {
        if (!NativeLink.available()) return;
        openRequest = NativeLink.send("open\tprofile");
        // Relire dès la prochaine demande après le retour en jeu.
        lastRequestAt = System.currentTimeMillis() - REFRESH_MS + 15_000L;
    }

    /** À chaque image, sur le fil du jeu. */
    public static void tick() {
        if (openRequest != 0L && NativeLink.poll(openRequest) != NativeLink.PENDING) openRequest = 0L;
        if (request == 0L) return;
        String reply = NativeLink.poll(request);
        if (reply == NativeLink.PENDING) return;
        request = 0L;
        String[] parts = reply.split("\t", -1);
        if (parts.length < 4 || !"profile".equals(parts[0])) {
            // Launcher sans cette question, ou erreur réseau : on ne redemande pas avant la prochaine fenêtre.
            loaded = true;
            return;
        }
        banner = parts[1];
        accent = parts[2];
        title = parts[3];
        loaded = true;
    }
}
