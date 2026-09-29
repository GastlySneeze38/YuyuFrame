package net.minecraft.client.renderer;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Stub compile-only 26.3 — ex-{@code DynamicUniforms} (26.2), renommé.
 * Règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>{@code writeTransform(modelView, couleur, décalage, matriceTexture)} :
 * même ordre d'ARGUMENTS qu'en 26.2, mais le bloc écrit a changé d'ordre —
 * {@code DynamicGpuData$Transform.write} pose ModelViewMat, TextureMat,
 * ColorModulator, ModelOffset (javap), comme {@code dynamictransforms.glsl}
 * de la 26.3. Nos shaders doivent déclarer le bloc dans ce même ordre.
 */
public class DynamicGpuData {

    private DynamicGpuData() {
    }

    public GpuBufferSlice writeTransform(Matrix4f modelView, Vector4f colorModulator,
                                         Vector3f modelOffset, Matrix4f textureMatrix) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
