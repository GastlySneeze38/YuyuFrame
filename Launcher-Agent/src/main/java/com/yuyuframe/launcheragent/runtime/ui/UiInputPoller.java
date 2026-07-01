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
    public boolean leftDown, rightDown;
    protected boolean prevLeftDown, prevRightDown;
    public boolean leftClicked, rightClicked; // "juste pressé cette frame"

    /** À appeler une fois par frame, avant de lire mouseX/mouseY/leftClicked/etc. */
    public final void poll() {
        try {
            prevLeftDown = leftDown;
            prevRightDown = rightDown;
            readState();
            leftClicked = leftDown && !prevLeftDown;
            rightClicked = rightDown && !prevRightDown;
        } catch (Throwable t) {
            LauncherLog.err("[UiInputPoller] poll: " + t);
        }
    }

    /** Doit renseigner mouseX/mouseY/leftDown/rightDown pour la frame courante. */
    protected abstract void readState() throws Exception;
}
