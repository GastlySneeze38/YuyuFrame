package com.yuyuframe.launcheragent.apimixin.data;

/**
 * Marque le menu Échap du jeu ({@code PauseScreen}, {@code GameMenuScreen} en
 * Yarn). Posée par un mixin de chaque version ({@code PauseScreenTagMixin*}),
 * qui ne fait qu'ajouter cette interface : le reste du code demande
 * {@code screen instanceof PauseScreenTag} sans connaître la classe du jeu,
 * dont le nom change d'une version à l'autre.
 */
public interface PauseScreenTag {
}
