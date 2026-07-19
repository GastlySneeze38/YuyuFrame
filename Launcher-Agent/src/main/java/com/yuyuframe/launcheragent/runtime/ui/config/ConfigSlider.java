package com.yuyuframe.launcheragent.runtime.ui.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marque un champ {@code float} comme un curseur numérique — voir {@link ConfigToggle} pour le principe général. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigSlider {
    String name();
    String description() default "";
    String category() default "Général";
    float min();
    float max();
    float step() default 1f;

    /**
     * Nom d'un champ int (typiquement un {@link ConfigDropdown}) DU MÊME
     * module — quand non-vide, ce curseur n'est activé/interactif que si ce
     * champ vaut {@link #dependsOnValue} ; sinon il reste visible mais
     * grisé/non-cliquable (voir ConfigScreenBuilder). Vide (défaut) = jamais
     * dépendant, toujours actif — comportement inchangé pour tous les
     * curseurs existants.
     */
    String dependsOnField() default "";
    int dependsOnValue() default 0;
}
