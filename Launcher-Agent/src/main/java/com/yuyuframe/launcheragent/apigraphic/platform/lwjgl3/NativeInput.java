package com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3;

/**
 * Couche native d'entrée de {@link UiInputPollerModern} quand ce n'est PAS
 * GLFW — introduite pour la 26.3, où Minecraft est passé de GLFW à SDL3.
 *
 * <p>Le poller garde toute sa logique (appui jamais perdu, table des noms,
 * combinaisons, édition de texte) et continue de raisonner en CODES GLFW :
 * c'est la langue du reste de l'agent (écrans, modules, réglages
 * enregistrés). Une implémentation fait donc deux choses :
 * <ul>
 *   <li>répondre aux lectures instantanées que le poller faisait jusqu'ici
 *       auprès de GLFW (curseur, tailles, état d'une touche, nom d'une
 *       touche) — en codes GLFW ;</li>
 *   <li>traduire ses propres codes vers les codes GLFW, pour les événements
 *       qu'elle pousse dans le poller ({@link UiInputPollerModern#onKeyEvent}
 *       etc.) et pour ceux que les écrans reçoivent de Minecraft.</li>
 * </ul>
 * Les événements, eux, ne passent pas par cette interface : sans callbacks
 * natifs à chaîner, ce sont des mixins de la tranche de version qui les
 * relaient depuis les gestionnaires de Minecraft.
 *
 * <p>Sans implémentation (toutes les versions GLFW), le poller appelle GLFW
 * lui-même, exactement comme avant.
 */
public interface NativeInput {

    /** Position du curseur en coordonnées FENÊTRE, origine haut-gauche (comme {@code glfwGetCursorPos}). */
    void cursorPos(double[] xOut, double[] yOut);

    /** Taille de la fenêtre en coordonnées fenêtre (comme {@code glfwGetWindowSize}). */
    void windowSize(int[] wOut, int[] hOut);

    /** Taille du framebuffer en pixels (comme {@code glfwGetFramebufferSize}). */
    void framebufferSize(int[] wOut, int[] hOut);

    /** Touche maintenue, désignée par son code GLFW. */
    boolean isKeyDown(int glfwKey);

    /** Libellé imprimable de la touche (comme {@code glfwGetKeyName(code, 0)}), {@code null} s'il n'y en a pas. */
    String keyName(int glfwKey);

    /** Code de touche natif → code GLFW, {@code -1} si la touche n'a pas d'équivalent. */
    int toGlfwKey(int nativeKey);

    /** Bouton de souris natif → index GLFW (0 gauche, 1 droit, 2 milieu, 3+ latéraux), {@code -1} si inconnu. */
    int toGlfwButton(int nativeButton);

    /**
     * Appelé à chaque lecture de l'état ({@code readState}), sur le fil de
     * rendu — pour ce qui doit suivre l'état de l'interface d'une image à
     * l'autre (SDL : démarrer/arrêter la saisie de texte selon
     * {@link com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller#textInputActive}).
     */
    void frame();
}
