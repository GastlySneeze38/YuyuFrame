/**
 * <b>Salle d'attente — ce paquet n'est PAS une destination définitive.</b>
 *
 * <p>Il ne reste ici que ce qui ne peut pas être RANGÉ, seulement DÉCOUPÉ,
 * parce que ça empile plusieurs couches :
 *
 * <ul>
 *   <li>{@code UiVanillaItemRenderer} (1374 l.) — pont vers les renderers DU
 *       JEU (icônes d'items, textures de conteneur), avec deux modèles de
 *       frame (immédiat et différé) et leurs files d'attente.</li>
 * </ul>
 *
 * <h2>gl2 et gl3 sont séparés (2026-09-10)</h2>
 *
 * {@code UiPrimitiveRenderer} faisait 1806 lignes et mélangeait les deux ères
 * GL. Il en fait moins de 250 et n'en connaît plus aucune. Ses six effets —
 * rect arrondi, vignette, icône, dégradé bilinéaire, dégradé multi-paliers,
 * FX — sont partis avec LEURS shaders, LEURS programmes et LEURS uniformes
 * vers {@code era/gl2/Gl2PrimitiveRenderer} et
 * {@code era/gl3/Gl3PrimitiveRenderer}, chacun câblé sur son backend.
 *
 * <p><b>À la main, effet par effet, pas par script.</b> Trois tentatives
 * scriptées avaient produit du code qui ne compilait pas, pour une raison
 * structurelle : les deux ères n'étaient pas seulement des méthodes voisines,
 * elles étaient entrelacées DANS certaines méthodes — {@code drawIcon} avait
 * les deux chemins inlinés dans un même corps, {@code isVignetteAvailable}
 * rendait un booléen différent par ère. Aucun découpage par motif ne survit à
 * ça. La reprise à la main a traité un effet à la fois, avec une compilation
 * verte après chacun (v1037 à v1044) : tant qu'un effet n'était pas migré, son
 * backend le déclinait et la façade retombait sur l'ancien code — l'arbre n'a
 * jamais été cassé entre deux étapes.
 *
 * <p>Trois effets ont demandé plus qu'un déplacement :
 * <ul>
 *   <li><b>l'icône</b> — les deux ères étaient dans le même corps de méthode,
 *       et le cache de textures ({@code IconTextures}) leur était commun ;
 *       chaque ère a désormais le sien, ce qui ne coûte rien puisqu'une seule
 *       est chargée par process ;</li>
 *   <li><b>la vignette</b> — son {@code isVignetteAvailable} testait l'ère
 *       pour rendre un booléen ; il est devenu {@code vignetteAvailable()} sur
 *       le contrat, et la façade ne fait plus que le relayer ;</li>
 *   <li><b>le FX</b> — ses trois compositions (ombre portée, contour creux,
 *       dégradé vertical) ne sont pas du code d'ère : ce sont des formules
 *       fixes au-dessus du même effet. Elles sont remontées dans
 *       {@code UiRenderer}, écrites une fois, au-dessus du contrat.</li>
 * </ul>
 *
 * <p>Le clip stencil ({@code beginRoundedClip}/{@code endRoundedClip}) est
 * resté : il est identique dans les deux ères, seul le rect qui peuple son
 * masque est spécifique, et celui-ci passe par le registre de backends.
 *
 * <p><b>{@code UiTextRenderer} était parti avant (2026-09-09)</b> — premier
 * des trois découpé, et modèle pour les deux autres. Ses 729 lignes se sont
 * réparties en {@code text/UiTextLayout} (mesure, calcul pur), {@code
 * era/glsupport/FontAtlasTextures} (atlas → texture GPU, la moitié du fichier
 * et rien à voir avec « dessiner du texte »), {@code era/gl2/Gl2TextRenderer},
 * {@code era/gl3/Gl3TextRenderer}, et le contrat {@code UiBackend.text}.
 *
 * <p>Reste donc {@code UiVanillaItemRenderer}, dont le découpage bute sur une
 * vraie question et pas sur du rangement : ses deux files d'attente
 * ({@code pendingModernItemIcons}, {@code pendingModernGuiBlits}) sont
 * partagées lexicalement entre gl3 et blaze3d, et ses cinq méthodes de vidage
 * sont appelées depuis des Mixins — les séparer demande d'abord de décider ce
 * que le contrat dit du cycle de vie d'une frame.
 */
package com.yuyuframe.launcheragent.apigraphic.render;
