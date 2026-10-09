package com.yuyuframe.launcheragent.apigraphic.hud;

/**
 * L'éditeur de HUD est-il en train de dessiner ses boîtes ?
 *
 * <p>On y place des éléments qu'on doit voir « pleins » pour juger de leur
 * taille et de leur place. Or beaucoup n'ont rien à montrer au moment où l'on
 * règle : pas d'armure sur soi, aucun effet de potion, pas de ping en solo —
 * et rien du tout depuis l'écran-titre, où il n'y a pas de joueur. Tant que
 * ce drapeau est levé, les sources de données rendent donc un exemple là où
 * elles n'ont rien de réel ({@code runtime.game.PlayerData}) ; ce qui existe
 * vraiment reste affiché tel quel.
 *
 * <p>Levé le temps du dessin de l'éditeur, sur le fil de rendu, et jamais
 * ailleurs : le HUD en jeu ne montre que du vrai.
 */
public final class HudPreview {

    private HudPreview() {
    }

    private static boolean active;

    public static boolean active() {
        return active;
    }

    public static void set(boolean on) {
        active = on;
    }
}
