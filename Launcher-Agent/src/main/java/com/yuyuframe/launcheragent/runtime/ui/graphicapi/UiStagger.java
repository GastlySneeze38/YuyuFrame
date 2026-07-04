package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

/**
 * Calcule un délai croissant par index — pour faire apparaître une LISTE
 * d'éléments (cartes du menu principal, lignes d'une config...) en cascade
 * au lieu de toutes d'un coup. Le délai obtenu se passe directement au
 * constructeur de {@link UiTransition} (paramètre {@code delaySeconds}).
 */
public final class UiStagger {
    private UiStagger() {}

    public static float delayFor(int index, float perItemDelaySeconds) {
        return Math.max(0, index) * perItemDelaySeconds;
    }

    /** Variante plafonnée — au-delà de {@code maxDelaySeconds}, une longue liste n'attend plus indéfiniment avant que ses derniers éléments n'apparaissent. */
    public static float delayFor(int index, float perItemDelaySeconds, float maxDelaySeconds) {
        return Math.min(delayFor(index, perItemDelaySeconds), maxDelaySeconds);
    }
}
