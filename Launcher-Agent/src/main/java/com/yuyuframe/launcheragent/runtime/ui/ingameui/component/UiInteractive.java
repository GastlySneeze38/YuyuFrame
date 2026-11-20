package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

/**
 * Base de tout ce qui se clique : survol, enfoncement et éclat de clic, tous
 * animés aux mêmes vitesses ({@link UiTheme.Motion}). Une sous-classe appelle
 * {@link #updateStates} en tête de son dessin puis lit {@link #hover},
 * {@link #press} et {@link #pulse()} ; elle réagit au clic dans
 * {@link #onActivate()}.
 */
public abstract class UiInteractive extends UiWidget {

    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    /** L'appui claque, le relâchement se repose. */
    private final UiAnimatedFloat pressAnim = new UiAnimatedFloat(0f, UiTheme.Motion.PRESS, UiTheme.Motion.RELEASE);
    /** Éclat du clic : bref et sans rebond, il se joue des dizaines de fois par partie. */
    private final UiTransition clickAnim = new UiTransition(UiTheme.Motion.CLICK_PULSE_S, 0f, UiEasing.UI_OUT);
    private boolean leftDown;
    private float pulseValue;
    private boolean pulsing;

    /** Inactif : ne réagit ni au survol ni au clic. */
    public boolean disabled;

    /** Survol, 0 à 1, valable après {@link #updateStates}. */
    protected float hover;
    /** Enfoncement, 0 à 1, valable après {@link #updateStates}. */
    protected float press;

    protected UiInteractive() {
        super(0f, 0f, 0f, 0f);
    }

    protected UiInteractive(float x, float y, float w, float h) {
        super(x, y, w, h);
    }

    /** À appeler une fois en tête de {@code draw} : fait avancer les animations. */
    protected void updateStates(double mouseX, double mouseY) {
        boolean over = !disabled && contains(mouseX, mouseY);
        hoverAnim.setTarget(over ? 1f : 0f);
        pressAnim.setTarget(over && leftDown ? 1f : 0f);
        hover = hoverAnim.get();
        press = pressAnim.get();
        // eased() fait avancer l'horloge : un seul appel par image.
        pulseValue = clickAnim.eased();
        pulsing = !clickAnim.isFinished();
    }

    /**
     * Avancement de l'éclat de clic (peut dépasser 1 au milieu du rebond), ou
     * {@code -1} quand aucun n'est en cours. Garder le test sur {@code < 0} :
     * tester la valeur ferait disparaître l'éclat en plein dépassement.
     */
    protected float pulse() {
        return pulsing ? pulseValue : -1f;
    }

    /** Rejoue l'éclat sans action (un changement venu d'ailleurs). */
    public void playPulse() {
        clickAnim.replay();
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        leftDown = input.leftDown;
    }

    @Override
    public final void onClick() {
        if (disabled) return;
        // Avant l'action : elle ouvre souvent un autre écran, l'éclat doit
        // partir avant, il reste visible pendant la transition.
        clickAnim.replay();
        onActivate();
    }

    /** Le clic. */
    protected abstract void onActivate();
}
