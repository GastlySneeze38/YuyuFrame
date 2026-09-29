package com.mojang.blaze3d.shaders;

import net.minecraft.resources.Identifier;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>INTERFACE : c'est ce qui permet à {@code Blaze3DGpu261} de l'implémenter
 * par une vraie classe nommée, là où l'adaptateur réflexif devait fabriquer un
 * {@code java.lang.reflect.Proxy}.
 */
public interface ShaderSource {

    String get(Identifier id, ShaderType type);
}
