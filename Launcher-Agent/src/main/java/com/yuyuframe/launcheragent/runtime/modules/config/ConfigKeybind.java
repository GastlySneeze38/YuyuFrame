package com.yuyuframe.launcheragent.runtime.modules.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marque un champ {@code String} (nom de touche) comme un bouton d'assignation clavier — voir {@link ConfigToggle} pour le principe général. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigKeybind {
    String name();
    String description() default "";
    String category() default "Général";
}
