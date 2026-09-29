package net.minecraft.client.renderer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Stub compile-only 26.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.2 : {@code writeTransform} garde ses 4 paramètres mais prend les
 * classes JOML concrètes ({@code Matrix4f}, {@code Vector4f}, {@code Vector3f})
 * au lieu des interfaces ({@code Matrix4fc}…) de la 26.1.2 — le descripteur
 * change, un stub resté en {@code *fc} donnerait un {@code NoSuchMethodError}.
 * Vérifié par javap.
 */
public abstract class DynamicUniforms {

    private DynamicUniforms() {
    }

    public GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator,
                                         Vector3f modelOffset, Matrix4f textureMatrix) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
