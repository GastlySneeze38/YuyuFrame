package net.minecraft.client.gl;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

/** Stub compile-only 1.21.11, nom Yarn ({@code hny}) — voir {@code com.mojang.blaze3d.systems.RenderSystem}. */
public class DynamicUniforms {

    private DynamicUniforms() {
    }

    public GpuBufferSlice write(Matrix4fc modelView, Vector4fc colorModulator, Vector3fc modelOffset, Matrix4fc textureMatrix) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
