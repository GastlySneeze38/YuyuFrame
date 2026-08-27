package com.yuyuframe.launcheragent.apigraphic.anim;

/**
 * Oscillateur "respiration" — va-et-vient doux et SANS FIN entre 0 et 1,
 * piloté par l'horloge, pour signaler qu'un élément est vivant/actif (bande
 * d'activation d'un module, indicateur en cours...).
 *
 * <p>Volontairement STATIQUE et sans état d'instance, contrairement à {@link
 * UiAnimatedFloat} (qui converge vers une cible) et {@link UiTransition} (qui
 * joue une fois puis s'arrête) : une respiration n'a ni cible ni fin, sa
 * valeur ne dépend QUE du temps. En conséquence, tous les éléments qui
 * respirent au même rythme sont naturellement EN PHASE — ce qui se lit comme
 * un effet voulu, là où des oscillateurs indépendants démarrés à des instants
 * différents produiraient un scintillement désordonné.
 *
 * <p>Courbe SINUS et non triangulaire : une rampe linéaire change de sens
 * brutalement aux extrémités, ce que l'œil perçoit comme un à-coup. Le sinus
 * ralentit naturellement en haut et en bas du cycle — c'est ce qui donne la
 * sensation d'inspiration/expiration plutôt que de clignotement.
 */
public final class UiBreathe {
    private UiBreathe() {}

    /**
     * Origine de temps FIXÉE au chargement de la classe plutôt que
     * {@code System.currentTimeMillis()} brut : les flottants perdent en
     * précision à mesure que la valeur grandit, et une horloge en
     * millisecondes depuis 1970 convertie en {@code float} n'a plus assez de
     * bits pour représenter une fraction de seconde proprement — la
     * respiration deviendrait saccadée. Repartir de zéro au lancement garde
     * des valeurs petites, donc lisses.
     */
    private static final long ORIGIN_MS = System.currentTimeMillis();

    /**
     * @param periodSeconds durée d'un cycle complet (bas -> haut -> bas).
     *                      Viser lent (2-4 s) : en dessous d'environ 1,5 s
     *                      l'effet cesse d'être perçu comme une respiration
     *                      pour devenir un clignotement, qui attire l'œil au
     *                      lieu de le rassurer.
     * @return valeur dans {@code [0,1]}.
     */
    public static float wave(float periodSeconds) {
        return wave(periodSeconds, 0f);
    }

    /**
     * @param phase décalage {@code [0,1]} d'un cycle — pour désynchroniser
     *              volontairement quelques éléments (effet de vague). À
     *              n'utiliser que si la désynchronisation est RECHERCHÉE :
     *              par défaut, être en phase est ce qui rend l'effet lisible.
     */
    public static float wave(float periodSeconds, float phase) {
        if (periodSeconds <= 0f) return 0f;
        float seconds = (System.currentTimeMillis() - ORIGIN_MS) / 1000f;
        double angle = 2.0 * Math.PI * ((seconds / periodSeconds) + phase);
        return (float) ((Math.sin(angle) + 1.0) * 0.5);
    }

    /**
     * Respiration recentrée autour de {@code 1.0} — pratique pour moduler
     * directement une intensité/opacité existante ({@code couleur ×
     * pulse(...)}) sans avoir à recomposer un lerp à chaque point d'appel.
     *
     * @param amplitude écart maximal de part et d'autre de 1 (ex: {@code
     *                  0.12f} -> oscille entre 0,88 et 1,12).
     */
    public static float pulse(float periodSeconds, float amplitude) {
        return 1f + (wave(periodSeconds) * 2f - 1f) * amplitude;
    }
}
