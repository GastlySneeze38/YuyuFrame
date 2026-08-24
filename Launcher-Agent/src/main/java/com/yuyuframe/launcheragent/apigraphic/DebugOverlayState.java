package com.yuyuframe.launcheragent.apigraphic;

/**
 * État partagé de l'overlay de debug rendu (ROADMAP-agent.md Phase 4.5,
 * "Overlay de debug rendu" — wireframe des rects dessinés, compteur de draw
 * calls, FPS de l'UI isolé du FPS jeu). Toggle via {@code /yf debug}/{@code
 * /yf perf} (voir {@code YfCommands}).
 *
 * VOLONTAIREMENT LIMITÉ à l'état + aux compteurs pour l'instant — pas de
 * dessin réel du wireframe/du HUD perf branché sur {@link GlBridge}/{@link
 * UiPrimitiveRenderer} : câbler ça maintenant reviendrait à instrumenter en
 * profondeur un moteur que la Phase 5 va reprendre de zéro (voir audit
 * ROADMAP-agent.md §3.3, "rework API graphique") — travail probablement
 * jeté au premier remaniement. Les compteurs ci-dessous sont prêts à être
 * incrémentés par le futur moteur ; l'affichage réel (wireframe, overlay
 * FPS) est un chantier séparé, à faire PENDANT ou APRÈS le rework, pas avant.
 */
public final class DebugOverlayState {
    private DebugOverlayState() {}

    /** Wireframe + compteur de draw calls — voir javadoc de classe pour la limite actuelle (état seulement, pas encore dessiné). */
    public static volatile boolean wireframeEnabled = false;

    /** FPS de l'UI (indépendant du FPS jeu) — même limite. */
    public static volatile boolean perfOverlayEnabled = false;

    /** Incrémenté par le moteur de rendu à CHAQUE appel GL de dessin — remis à zéro par {@link #beginFrame()}. Pas encore incrémenté nulle part (voir javadoc de classe) — prêt pour le rework. */
    public static volatile int drawCallCount = 0;

    private static long lastFrameNanos = 0L;
    private static volatile double uiFps = 0.0;

    /** À appeler une fois par frame UI (depuis le futur point d'accroche de rendu) — remet le compteur de draw calls à zéro et met à jour le FPS UI. */
    public static void beginFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            double deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0) uiFps = 1.0 / deltaSeconds;
        }
        lastFrameNanos = now;
        drawCallCount = 0;
    }

    public static double uiFps() { return uiFps; }
}
