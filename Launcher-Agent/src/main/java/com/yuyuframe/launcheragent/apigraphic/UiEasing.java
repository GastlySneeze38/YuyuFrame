package com.yuyuframe.launcheragent.apigraphic;

/**
 * Fonctions d'accélération standard — transforment une progression LINÉAIRE
 * 0..1 (temps écoulé / durée) en une progression "naturelle" (démarre vite
 * puis ralentit, léger dépassement puis retour...). Utilisées par
 * {@link UiTransition}, jamais par {@link UiAnimatedFloat} (qui a déjà sa
 * propre convergence exponentielle, pas besoin d'une courbe supplémentaire).
 */
public final class UiEasing {
    private UiEasing() {}

    @FunctionalInterface
    public interface Curve {
        /** {@code t} et le résultat sont normalement dans [0,1], mais {@link #EASE_OUT_BACK} dépasse légèrement 1 en cours de route (effet rebond voulu). */
        float apply(float t);
    }

    public static final Curve LINEAR = t -> t;

    /** Démarre vite, ralentit en fin — le plus polyvalent, à utiliser par défaut pour un fondu/glissement. */
    public static final Curve EASE_OUT_CUBIC = t -> 1f - pow3(1f - t);

    /** Symétrique : lent-vite-lent — utile pour une valeur qui va ET revient (ex: pulsation). */
    public static final Curve EASE_IN_OUT_QUAD = t -> t < 0.5f ? 2f * t * t : 1f - pow2(-2f * t + 2f) / 2f;

    /**
     * Léger dépassement au-delà de 1.0 puis retour — effet "pop"/rebond pour
     * l'ENTRÉE d'une carte/d'un widget (donne une sensation plus "physique"
     * qu'un simple fondu). PAS adapté à une sortie (le dépassement serait
     * perçu comme un raté si l'élément disparaît en même temps).
     */
    public static final Curve EASE_OUT_BACK = t -> {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float x = t - 1f;
        return 1f + c3 * pow3(x) + c1 * pow2(x);
    };

    private static float pow2(float x) { return x * x; }
    private static float pow3(float x) { return x * x * x; }
}
