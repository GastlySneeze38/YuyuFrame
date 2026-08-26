package com.yuyuframe.launcheragent.apigraphic.anim;

/**
 * Fonctions d'accélération standard — transforment une progression LINÉAIRE
 * 0..1 (temps écoulé / durée) en une progression "naturelle" (démarre vite
 * puis ralentit, léger dépassement puis retour...). Utilisées par
 * {@link UiTransition}, jamais par {@link UiAnimatedFloat} (qui a déjà sa
 * propre convergence exponentielle, pas besoin d'une courbe supplémentaire).
 *
 * Set Robert Penner complet (roadmap Phase 5.1) — {@code sine}/{@code quad}/
 * {@code cubic}/{@code quart}/{@code expo}/{@code circ}/{@code back}/{@code
 * elastic}/{@code bounce}, chacune en {@code IN}/{@code OUT}/{@code IN_OUT}.
 * Formules vérifiées contre la référence canonique (easings.net).
 *
 * La plupart des familles ({@code sine}..{@code circ}) ne déclarent QUE leur
 * variante {@code IN} — {@code OUT}/{@code IN_OUT} sont DÉRIVÉES via les deux
 * identités mathématiques standard (valables pour toute courbe qui ne
 * réajuste pas sa constante entre variantes) :
 * <pre>
 *   easeOut(t)   = 1 - easeIn(1 - t)
 *   easeInOut(t) = t &lt; 0.5 ? easeIn(2t)/2 : 1 - easeIn(2-2t)/2
 * </pre>
 * {@code back}/{@code elastic} restent chacune ENTIÈREMENT explicites (3
 * formules propres) : Penner utilise une constante de dépassement/oscillation
 * DIFFÉRENTE pour leur variante {@code IN_OUT} (plus prononcée que In/Out
 * pris isolément) — la dérivation générique ci-dessus ne la reproduit PAS
 * (vérifié algébriquement), une approximation aurait donné une courbe
 * visuellement différente de la référence. {@code bounce} n'a pas ce
 * problème (aucune constante à réajuster) — déclare seulement {@code OUT}
 * (la forme physique de référence, un objet qui rebondit en tombant),
 * {@code IN}/{@code IN_OUT} dérivées de {@code OUT} (mêmes identités,
 * miroir).
 */
public final class UiEasing {
    private UiEasing() {}

    @FunctionalInterface
    public interface Curve {
        /** {@code t} et le résultat sont normalement dans [0,1] — certaines courbes ({@code BACK}/{@code ELASTIC}) dépassent légèrement cette plage en cours de route (dépassement/oscillation voulus). */
        float apply(float t);
    }

    public static final Curve LINEAR = t -> t;

    // ── Dérivation générique (voir javadoc de classe) ─────────────────────
    private static Curve out(Curve in) { return t -> 1f - in.apply(1f - t); }
    private static Curve inOutFromIn(Curve in) {
        return t -> t < 0.5f ? in.apply(2f * t) / 2f : 1f - in.apply(2f - 2f * t) / 2f;
    }
    private static Curve in(Curve out) { return t -> 1f - out.apply(1f - t); }
    private static Curve inOutFromOut(Curve out) {
        return t -> t < 0.5f ? (1f - out.apply(1f - 2f * t)) / 2f : (1f + out.apply(2f * t - 1f)) / 2f;
    }

    // ── Sine ───────────────────────────────────────────────────────────────
    public static final Curve SINE_IN = t -> 1f - (float) Math.cos((t * Math.PI) / 2f);
    public static final Curve SINE_OUT = out(SINE_IN);
    public static final Curve SINE_IN_OUT = inOutFromIn(SINE_IN);

    // ── Quad (puissance 2) ───────────────────────────────────────────────
    public static final Curve QUAD_IN = t -> t * t;
    public static final Curve QUAD_OUT = out(QUAD_IN);
    public static final Curve QUAD_IN_OUT = inOutFromIn(QUAD_IN);

    // ── Cubic (puissance 3) ──────────────────────────────────────────────
    public static final Curve CUBIC_IN = t -> t * t * t;
    public static final Curve CUBIC_OUT = out(CUBIC_IN);
    public static final Curve CUBIC_IN_OUT = inOutFromIn(CUBIC_IN);

    /** Alias historique — démarre vite, ralentit en fin, le plus polyvalent (fondu/glissement par défaut). Identique à {@link #CUBIC_OUT}, conservé pour ne pas retoucher les ~7 sites d'appel existants. */
    public static final Curve EASE_OUT_CUBIC = CUBIC_OUT;

    // ── Quart (puissance 4) ──────────────────────────────────────────────
    public static final Curve QUART_IN = t -> t * t * t * t;
    public static final Curve QUART_OUT = out(QUART_IN);
    public static final Curve QUART_IN_OUT = inOutFromIn(QUART_IN);

    // ── Expo ─────────────────────────────────────────────────────────────
    public static final Curve EXPO_IN = t -> t <= 0f ? 0f : (float) Math.pow(2, 10 * t - 10);
    public static final Curve EXPO_OUT = out(EXPO_IN);
    public static final Curve EXPO_IN_OUT = inOutFromIn(EXPO_IN);

    // ── Circ ─────────────────────────────────────────────────────────────
    public static final Curve CIRC_IN = t -> 1f - (float) Math.sqrt(1f - t * t);
    public static final Curve CIRC_OUT = out(CIRC_IN);
    public static final Curve CIRC_IN_OUT = inOutFromIn(CIRC_IN);

    // ── Back (dépassement) — chaque variante explicite, voir javadoc de classe ──
    private static final float BACK_C1 = 1.70158f;
    private static final float BACK_C3 = BACK_C1 + 1f;
    private static final float BACK_C2 = BACK_C1 * 1.525f;

    public static final Curve BACK_IN = t -> BACK_C3 * t * t * t - BACK_C1 * t * t;
    /**
     * Léger dépassement au-delà de 1.0 puis retour — effet "pop"/rebond pour
     * l'ENTRÉE d'une carte/d'un widget (donne une sensation plus "physique"
     * qu'un simple fondu). PAS adapté à une sortie (le dépassement serait
     * perçu comme un raté si l'élément disparaît en même temps).
     */
    public static final Curve BACK_OUT = t -> {
        float x = t - 1f;
        return 1f + BACK_C3 * x * x * x + BACK_C1 * x * x;
    };
    public static final Curve BACK_IN_OUT = t -> {
        if (t < 0.5f) {
            float x = 2f * t;
            return (x * x * ((BACK_C2 + 1f) * x - BACK_C2)) / 2f;
        }
        float x = 2f * t - 2f;
        return (x * x * ((BACK_C2 + 1f) * x + BACK_C2) + 2f) / 2f;
    };
    /** Alias historique — voir {@link #BACK_OUT}, conservé pour lisibilité (même valeur). */
    public static final Curve EASE_OUT_BACK = BACK_OUT;

    // ── Elastic (oscillation) — chaque variante explicite, voir javadoc de classe ──
    private static final float ELASTIC_C4 = (2f * (float) Math.PI) / 3f;
    private static final float ELASTIC_C5 = (2f * (float) Math.PI) / 4.5f;

    public static final Curve ELASTIC_IN = t -> {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return -(float) Math.pow(2, 10 * t - 10) * (float) Math.sin((t * 10 - 10.75) * ELASTIC_C4);
    };
    public static final Curve ELASTIC_OUT = t -> {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) Math.pow(2, -10 * t) * (float) Math.sin((t * 10 - 0.75) * ELASTIC_C4) + 1f;
    };
    public static final Curve ELASTIC_IN_OUT = t -> {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        if (t < 0.5f) {
            return -((float) Math.pow(2, 20 * t - 10) * (float) Math.sin((20 * t - 11.125) * ELASTIC_C5)) / 2f;
        }
        return ((float) Math.pow(2, -20 * t + 10) * (float) Math.sin((20 * t - 11.125) * ELASTIC_C5)) / 2f + 1f;
    };

    // ── Bounce — seule OUT est explicite (forme physique de référence), voir javadoc de classe ──
    public static final Curve BOUNCE_OUT = t -> {
        float n1 = 7.5625f, d1 = 2.75f;
        if (t < 1f / d1) return n1 * t * t;
        if (t < 2f / d1) { float x = t - 1.5f / d1; return n1 * x * x + 0.75f; }
        if (t < 2.5f / d1) { float x = t - 2.25f / d1; return n1 * x * x + 0.9375f; }
        float x = t - 2.625f / d1;
        return n1 * x * x + 0.984375f;
    };
    public static final Curve BOUNCE_IN = in(BOUNCE_OUT);
    public static final Curve BOUNCE_IN_OUT = inOutFromOut(BOUNCE_OUT);
}
