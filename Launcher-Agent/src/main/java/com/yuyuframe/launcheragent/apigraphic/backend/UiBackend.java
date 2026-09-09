package com.yuyuframe.launcheragent.apigraphic.backend;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

/**
 * Ce que toute ère doit savoir faire — le contrat que {@code era/} implémente
 * et que la façade {@code UiRenderer} appelle.
 *
 * <h2>État : une seule primitive, volontairement</h2>
 *
 * Le contrat démarre avec {@link #roundedRect} et rien d'autre. C'est délibéré :
 * la forme d'une interface de rendu ne se décide pas sur le papier, elle se
 * vérifie sur un cas réel. Le rect arrondi est le bon cas — il existe déjà dans
 * les trois chemins (gl2, gl3, Blaze3D), avec les mêmes uniformes et la même
 * règle {@code radius<=0}, donc il exerce la frontière sans rien inventer.
 *
 * <p>Les autres primitives (icône, texte, vignette, dégradé, flou, clip) le
 * rejoindront au fur et à mesure du découpage des trois renderers de
 * {@code render/} — voir le {@code package-info} de ce paquet-là.
 *
 * <h2>Pourquoi un {@code boolean} de retour</h2>
 *
 * {@code true} = « j'ai dessiné ». {@code false} = « pas mon affaire, prends
 * ton chemin habituel ».
 *
 * <p>C'est une forme de TRANSITION, pas la forme finale. Elle permet
 * d'introduire le contrat sans toucher au comportement : une ère sans backend
 * répond {@code false}, l'appelant retombe exactement sur le code qu'il
 * exécutait hier. Quand les trois renderers auront été découpés et que chaque
 * ère aura un backend complet, ce booléen disparaîtra — un backend ne pourra
 * plus décliner.
 */
public interface UiBackend {

    /** Identifiant lisible, pour le log. */
    String id();

    /**
     * Rectangle à coins arrondis.
     *
     * @return {@code true} si l'ère l'a pris en charge ; {@code false} pour
     *         laisser l'appelant continuer sur son chemin historique
     */
    boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                        UiColor color, int vpWidth, int vpHeight);
}
