package com.yuyuframe.launcheragent.lwjgl2compat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sur une méthode {@code @Shadow} statique d'un mixin LWJGL : après tissage,
 * {@code LauncherMixinConfigPlugin} ajoute à la classe cible une méthode
 * publique statique du nom LWJGL 2 {@link #value()}, même descripteur, qui
 * délègue à la méthode LWJGL 3 ombrée.
 *
 * <p>Exemple : {@code @LegacyAlias("glGetFloat")} sur
 * {@code glGetFloatv(int, FloatBuffer)} recrée {@code GL11.glGetFloat}.
 *
 * <p>Nécessaire parce que Mixin refuse d'ajouter lui-même une méthode statique
 * non privée. Même mécanisme que {@code @CreateStub} de legacy-lwjgl3
 * (moehreag, LGPL-2.1). Rétention CLASS : jamais chargée à l'exécution, lue
 * dans le bytecode par le plugin.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LegacyAlias {
	String value();
}
