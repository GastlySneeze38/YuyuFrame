package net.minecraft.client.texture;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gl.GpuSampler;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gpq}) — {@code record} en jeu.
 * Pendant de {@code net.minecraft.client.gui.render.TextureSetup} en 26.1.2
 * (paquet différent).
 */
public final class TextureSetup {

    /** Pendant de {@code noTexture()} en 26.1.2. */
    public static TextureSetup empty() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static TextureSetup of(GpuTextureView texture, GpuSampler sampler) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    private TextureSetup() {
    }
}
