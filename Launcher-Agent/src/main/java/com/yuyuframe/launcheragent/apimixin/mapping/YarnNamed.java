package com.yuyuframe.launcheragent.apimixin.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque une classe écrite en noms YARN, à traduire AU CHARGEMENT vers les noms
 * du loader actif par {@code YarnNamedRemapper}.
 *
 * <h2>Pourquoi une annotation plutôt qu'un paquet</h2>
 *
 * Le remappeur sélectionnait jusqu'ici ses classes par PAQUET, ce qui imposait
 * de ranger le code typé 1.21.11 à part ({@code apimixin/typed/v1_21_11}) : les
 * Mixins de {@code apimixin/v1_21_11} ne doivent PAS passer par lui, Mixin les
 * traduit déjà par sa refmap. Avec ce marqueur, code typé et Mixins vivent dans
 * le même dossier ({@code AccessorBindings1211} à côté de ses accessors), et
 * c'est le fichier lui-même qui dit qu'il est traduit (choix de l'utilisateur,
 * 2026-09-13 : visible dans le fichier plutôt qu'une règle implicite).
 *
 * <h2>Règles</h2>
 * <ul>
 *   <li>À poser sur CHAQUE classe concernée, classes imbriquées comprises : une
 *       classe interne est un fichier {@code .class} distinct, qui n'hérite pas
 *       de l'annotation de sa classe englobante. Aucune classe ANONYME dans ce
 *       code — elle ne pourrait pas être annotée.</li>
 *   <li>Pas de {@code switch} sur une énumération dans une classe marquée :
 *       javac génère une classe synthétique ({@code Outer$1}, table
 *       {@code $SwitchMap$}) qui ne peut pas être annotée. Utiliser des
 *       {@code if}. Vu par {@code RemapCheck} sur {@code VanillaGuiSink1211}.</li>
 *   <li>Jamais sur un {@code @Mixin}.</li>
 *   <li>Oublier le marqueur ne se voit pas à la compilation : la classe part en
 *       jeu avec ses noms Yarn et lève {@code NoClassDefFoundError} au premier
 *       usage. Vérifier hors jeu avant tout test.</li>
 * </ul>
 *
 * <p>Rétention {@code CLASS} : lue dans le bytecode par ASM, jamais par
 * réflexion à l'exécution.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface YarnNamed {
}
