package net.minecraft.client.gl;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code com.mojang.blaze3d.shaders.ShaderSource}
 * côté Mojang, {@code fyy} officiel). Sa méthode s'appelle {@code get} dans
 * TOUS les espaces de noms (vérifié dans Yarn : official = intermediary =
 * {@code get}) — une implémentation reste donc un override après traduction.
 */
public interface ShaderSourceGetter {

    String get(Identifier id, ShaderType type);
}
