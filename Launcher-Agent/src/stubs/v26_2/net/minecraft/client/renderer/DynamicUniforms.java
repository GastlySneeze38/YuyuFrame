package net.minecraft.client.renderer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>La méthode s'appelle {@code writeTransform} ici, et {@code write} en
 * 1.21.11 : l'adaptateur réflexif ne la nommait pas du tout, il la retrouvait
 * « la seule à 4 paramètres rendant une {@code GpuBufferSlice} ». Le nom est
 * désormais vérifié sur le jar.
 */
public abstract class DynamicUniforms {

    private DynamicUniforms() {
    }

    public GpuBufferSlice writeTransform(Matrix4fc modelView, Vector4fc colorModulator,
                                         Vector3fc modelOffset, Matrix4fc textureMatrix) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
