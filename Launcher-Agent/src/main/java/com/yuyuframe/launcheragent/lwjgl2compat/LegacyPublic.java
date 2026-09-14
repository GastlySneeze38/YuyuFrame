package com.yuyuframe.launcheragent.lwjgl2compat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sur une méthode {@code @Unique private static} d'un mixin LWJGL : après
 * tissage, {@code LauncherMixinConfigPlugin} la rend publique dans la classe
 * cible — c'est ainsi qu'on AJOUTE une API LWJGL 2 absente de LWJGL 3
 * ({@code AL.create()}, {@code GL20.glShaderSource(int, ByteBuffer)}…).
 *
 * <p>Même mécanisme que {@code @Public} de legacy-lwjgl3 (moehreag,
 * LGPL-2.1). Rétention CLASS : lue dans le bytecode par le plugin.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LegacyPublic {
}
