/**
 * <b>Salle d'attente — ce paquet n'est PAS une destination définitive.</b>
 *
 * <p>Il ne reste ici que les fichiers qui ne peuvent pas être RANGÉS,
 * seulement DÉCOUPÉS, parce que chacun empile plusieurs couches :
 *
 * <ul>
 *   <li>{@code UiPrimitiveRenderer} (1983 l.) — deux ères entrelacées méthode
 *       par méthode ({@code drawXLegacy}/{@code drawXModern}, six paires
 *       {@code ensureXShaderInit}/{@code …Modern}) + 43 constantes GLSL en dur
 *       + la géométrie pure (coins arrondis, spinner, ripple, shimmer).</li>
 *   <li>{@code UiVanillaItemRenderer} (1374 l.) — pont vers les renderers DU
 *       JEU (icônes d'items, textures de conteneur), avec deux modèles de
 *       frame (immédiat et différé) et leurs files d'attente.</li>
 * </ul>
 *
 * <p><b>{@code UiTextRenderer} est parti (2026-09-09)</b> — premier des trois
 * découpé, et modèle pour les deux autres. Ses 729 lignes se sont réparties en
 * {@code text/UiTextLayout} (mesure, calcul pur), {@code
 * era/glsupport/FontAtlasTextures} (atlas → texture GPU, la moitié du fichier
 * et rien à voir avec « dessiner du texte »), {@code era/gl2/Gl2TextRenderer},
 * {@code era/gl3/Gl3TextRenderer}, et le contrat {@code UiBackend.text}.
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
