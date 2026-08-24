package com.yuyuframe.launcheragent.apigraphic;

/**
 * Fondu d'entrée pour du contenu qui arrive de façon ASYNCHRONE (icône
 * distante téléchargée en HTTP, voir UiRemoteImage) — capacité absente du
 * moteur jusqu'ici (voir audit runtime/ui/ : apparition brute dès que
 * {@code get()} renvoie une image non-nulle, aucune transition). PAS un
 * doublon de {@link UiTransition} : celui-ci gère show()/hide() explicites
 * décidés par l'appelant ; {@link UiAsyncFade} n'a qu'UN seul événement
 * possible — "le contenu vient d'arriver" — déclenché une seule fois, la
 * première fois que {@link #markReady()} est appelé, jamais annulable
 * ensuite (contrairement à un hover qui peut repartir en arrière).
 *
 * Usage : un champ {@code UiAsyncFade fade = new UiAsyncFade();} par
 * ressource async ; appeler {@code fade.markReady()} inconditionnellement
 * chaque frame où le contenu est disponible (idempotent après le premier
 * appel) ; lire {@code fade.alpha()} au moment de dessiner (voir
 * {@code UiRenderer#drawIcon(..., alpha, ...)}).
 */
public final class UiAsyncFade {

    private static final long DEFAULT_DURATION_MS = 220L;

    private final long durationMs;
    private long readyAtMs = -1L;

    public UiAsyncFade() {
        this(DEFAULT_DURATION_MS);
    }

    public UiAsyncFade(long durationMs) {
        this.durationMs = Math.max(1L, durationMs);
    }

    /** Idempotent — n'affecte le timing du fondu qu'au tout premier appel. */
    public void markReady() {
        if (readyAtMs < 0) readyAtMs = System.currentTimeMillis();
    }

    /** {@code true} tant que {@link #markReady()} n'a jamais été appelé (rien à dessiner encore, laisse l'appelant afficher son propre placeholder). */
    public boolean isPending() {
        return readyAtMs < 0;
    }

    /** 0 avant {@link #markReady()}, puis 0→1 sur {@code durationMs}, lissé (EASE_OUT_CUBIC). */
    public float alpha() {
        if (readyAtMs < 0) return 0f;
        float t = (System.currentTimeMillis() - readyAtMs) / (float) durationMs;
        t = Math.max(0f, Math.min(1f, t));
        return UiEasing.EASE_OUT_CUBIC.apply(t);
    }
}
