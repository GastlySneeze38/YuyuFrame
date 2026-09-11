/**
 * Ère Blaze3D, variante <b>1.21.11</b> — code TYPÉ, sans réflexion.
 *
 * <h2>Pourquoi une unité de compilation à part</h2>
 *
 * La 1.21.11 et la 26.1.2 exposent toutes deux des classes
 * {@code com.mojang.blaze3d.*} de même nom mais d'API différente (la 1.21.11
 * n'a ni {@code ColorTargetState} ni {@code DepthStencilState}, décrit l'état
 * couleur/profondeur par {@code withBlend}/{@code withDepthTestFunction}…).
 * Les deux ne peuvent pas cohabiter sur un même classpath de compilation.
 * {@code build.bat} compile donc ce dossier ({@code src/main_1_21_11/java})
 * dans une passe SÉPARÉE, contre {@code src/stubs_1_21_11} seulement — jamais
 * contre {@code src/stubs} (26.1.2) — puis l'embarque dans le même jar.
 *
 * <h2>Noms utilisés, et traduction au chargement</h2>
 *
 * <ul>
 *   <li>Classes {@code com.mojang.blaze3d.*} de premier niveau (et leurs
 *       imbriquées non obfusquées) : sous leur vrai nom, identique quel que
 *       soit le loader.</li>
 *   <li>{@code net.minecraft.*} et imbriquées obfusquées ({@code Identifier},
 *       {@code UniformType}, {@code GpuSampler}, {@code VertexFormat$DrawMode}…) :
 *       sous leur NOM YARN. Toute classe de CE paquet est réécrite au chargement
 *       par {@code YarnNamedRemapper} vers les noms du loader actif
 *       (intermédiaire sous Fabric, officiel en vanilla). Ne jamais déplacer ce
 *       code hors du paquet : non traduit, il chercherait des classes Yarn
 *       inexistantes en jeu.</li>
 *   <li>Une interface du jeu s'implémente par une CLASSE, jamais par une lambda
 *       (le remappeur ne traduit pas la méthode fonctionnelle d'une lambda).</li>
 * </ul>
 *
 * <p>Chargé uniquement sur 1.21.11 : sur une autre version, aucune classe de
 * ce paquet n'est jamais référencée, donc jamais chargée.
 */
package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;
