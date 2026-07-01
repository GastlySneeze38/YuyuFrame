package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

/**
 * État souris/clavier pollé chaque frame depuis un Mixin global sur le render
 * loop — jamais via Screen.mouseClicked/keyPressed (voir UiDrawable).
 *
 * Deux implémentations, une par famille LWJGL — choisie par le Mixin global
 * de CHAQUE version (GlobalUiRenderMixin en 1.21+, son équivalent v1_8 pour
 * 1.8.9), jamais de branchement runtime ici :
 *   - {@link UiInputPollerModern} : LWJGL3/GLFW (1.13+, dont 1.21)
 *   - {@link UiInputPollerLegacy} : LWJGL2 org.lwjgl.input.Mouse/Keyboard (1.8.9)
 *
 * Coordonnées Y — PAS symétriques entre les deux implémentations, mais
 * chacune normalise déjà vers l'espace pixels FRAMEBUFFER origine bas-gauche
 * (mêmes unités que gl_FragCoord en GLSL, voir UiRenderer) :
 *   - LWJGL2 Mouse.getY() : déjà dans cet espace nativement, aucune conversion.
 *   - LWJGL3 glfwGetCursorPos() : coordonnées "fenêtre" origine haut-gauche —
 *     UiInputPollerModern applique la mise à l'échelle fenêtre→framebuffer
 *     (écrans HiDPI) PUIS le flip Y, voir son readState().
 */
public abstract class UiInputPoller {

    public double mouseX, mouseY;
    /** Taille framebuffer courante (mêmes unités que mouseX/Y et gl_FragCoord) — pour layout plein écran. */
    public int fbWidth, fbHeight;
    public boolean leftDown, rightDown;
    protected boolean prevLeftDown, prevRightDown;
    public boolean leftClicked, rightClicked; // "juste pressé cette frame"

    /** Touche d'ouverture du menu (Right Shift) — utilisable même sans écran ouvert, voir readMenuKeyDown(). */
    public boolean menuKeyDown, menuKeyPressed;
    private boolean prevMenuKeyDown;

    /** Delta de molette de cette frame (positif = vers le haut) — voir readScrollDelta(). */
    public int scrollDelta;

    /** À appeler une fois par frame, avant de lire mouseX/mouseY/leftClicked/etc. */
    public final void poll() {
        try {
            prevLeftDown = leftDown;
            prevRightDown = rightDown;
            prevMenuKeyDown = menuKeyDown;
            readState();
            leftClicked = leftDown && !prevLeftDown;
            rightClicked = rightDown && !prevRightDown;
            menuKeyDown = readMenuKeyDown();
            menuKeyPressed = menuKeyDown && !prevMenuKeyDown;
            scrollDelta = readScrollDelta();
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPoller] poll: " + t);
        }
    }

    /** Doit renseigner mouseX/mouseY/fbWidth/fbHeight/leftDown/rightDown pour la frame courante. */
    protected abstract void readState() throws Exception;

    /** État courant de la touche Right Shift — chaque implémentation utilise sa propre constante native. */
    protected abstract boolean readMenuKeyDown() throws Exception;

    /** Delta de molette depuis le dernier poll() — chaque implémentation gère sa propre source (event/callback). */
    protected abstract int readScrollDelta() throws Exception;

    /**
     * À appeler UNIQUEMENT quand un widget est en mode "capture" (UiKeybindButton
     * en attente d'une touche) — PAS chaque frame inconditionnellement, pour ne
     * jamais interférer avec le jeu quand aucun widget n'écoute. Renvoie le nom
     * de la première touche pressée cette frame (ex: "A", "F1", "SPACE"),
     * null si aucune. Le nom sert de format d'échange indépendant de la version
     * (les codes numériques LWJGL2/GLFW ne coïncident pas d'une version à l'autre).
     */
    public abstract String pollAnyKeyJustPressed();
}
