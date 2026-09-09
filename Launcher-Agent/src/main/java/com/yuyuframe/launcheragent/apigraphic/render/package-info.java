/**
 * <b>Salle d'attente — ce paquet n'est PAS une destination définitive.</b>
 *
 * <p>Il ne reste ici que les trois fichiers qui ne peuvent pas être RANGÉS,
 * seulement DÉCOUPÉS, parce que chacun empile plusieurs couches :
 *
 * <ul>
 *   <li>{@code UiPrimitiveRenderer} (2033 l.) — deux ères entrelacées méthode
 *       par méthode ({@code drawXLegacy}/{@code drawXModern}, six paires
 *       {@code ensureXShaderInit}/{@code …Modern}) + 43 constantes GLSL en dur
 *       + la géométrie pure (coins arrondis, spinner, ripple, shimmer).</li>
 *   <li>{@code UiTextRenderer} (768 l.) — mesure/troncature (calcul pur) mêlées
 *       à la rastérisation de deux ères.</li>
 *   <li>{@code UiVanillaItemRenderer} (1391 l.) — pont vers les renderers DU
 *       JEU (icônes d'items, textures de conteneur), avec deux modèles de
 *       frame (immédiat et différé) et leurs files d'attente.</li>
 * </ul>
 *
 * <p>Destination prévue quand ils seront découpés :
 * <ul>
 *   <li>le calcul pur (géométrie, formes, mise en page du texte) → {@code draw/}
 *       et {@code text/} ;</li>
 *   <li>le code GL par ère → {@code era/gl2/} et {@code era/gl3/}, avec leur
 *       GLSL ;</li>
 *   <li>le pont vers les objets du jeu → le contrat {@code backend/} et sa
 *       réalisation dans chaque ère.</li>
 * </ul>
 *
 * <p>Tant que ce découpage n'a pas eu lieu, {@code era/gl2/}, {@code era/gl3/},
 * {@code draw/} et {@code backend/} n'existent pas : ils seraient vides, et un
 * dossier vide ne dit rien. Ce fichier est là pour que la prochaine lecture de
 * l'arborescence ne prenne pas {@code render/} pour une décision d'architecture.
 */
package com.yuyuframe.launcheragent.apigraphic.render;
