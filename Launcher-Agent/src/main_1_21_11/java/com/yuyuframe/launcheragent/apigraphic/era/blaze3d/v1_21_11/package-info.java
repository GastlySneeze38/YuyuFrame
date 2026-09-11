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
 * <h2>Ce que ce paquet peut et ne peut pas nommer</h2>
 *
 * <ul>
 *   <li>Peut : les classes {@code com.mojang.blaze3d.*} de premier niveau et
 *       leurs imbriquées non obfusquées — identiques quel que soit le loader.</li>
 *   <li>Ne peut pas : {@code net.minecraft.*} et les imbriquées obfusquées
 *       ({@code Identifier}, {@code UniformType}, {@code GpuSampler},
 *       {@code VertexFormat$a/$b}…), dont le nom dépend du loader. Ceux-là
 *       passent par des invokers Mixin de {@code apimixin/v1_21_11}.</li>
 * </ul>
 *
 * <p>Chargé uniquement sur 1.21.11 : sur une autre version, aucune classe de
 * ce paquet n'est jamais référencée, donc jamais chargée.
 */
package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;
