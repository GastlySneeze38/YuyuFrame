package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stub compile-only 26.2 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Écarts avec la 26.1.2, vérifiés par javap sur le jar client 26.2 :
 * <ul>
 *   <li>{@code createRenderPass} : la couleur d'effacement est un
 *       {@code Optional<Vector4fc>} (plus un {@code OptionalInt}) ;</li>
 *   <li>{@code writeToTexture(GpuTexture, NativeImage, …)} ne prend plus que
 *       4 entiers {@code (mipLevel, depth, destX, destY)} et envoie l'image
 *       ENTIÈRE à cette position — la variante à 8 entiers (largeur, hauteur,
 *       sauts de pixels et de lignes) a disparu.</li>
 * </ul>
 */
public abstract class CommandEncoder {

    private CommandEncoder() {
    }

    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToBuffer(GpuBufferSlice target, ByteBuffer data) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToTexture(GpuTexture target, NativeImage source) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToTexture(GpuTexture target, NativeImage source, int mipLevel, int depth, int destX, int destY) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
