package com.yuyuframe.launcheragent.runtime.modules.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque un champ {@code boolean} d'un {@link com.yuyuframe.launcheragent.runtime.modules.LauncherModule}
 * comme un réglage à cocher — {@link com.yuyuframe.launcheragent.runtime.modules.ConfigScreenBuilder}
 * génère la ligne (UiToggle) automatiquement, aucun code d'écran à écrire.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigToggle {
    String name();
    String description() default "";
    /** Onglet dans lequel la ligne apparaît — onglets créés dans l'ordre de première apparition, voir ConfigScreenBuilder. */
    String category() default "Général";
}
