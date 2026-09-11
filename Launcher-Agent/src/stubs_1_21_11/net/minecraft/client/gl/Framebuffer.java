package net.minecraft.client.gl;

import com.mojang.blaze3d.textures.GpuTextureView;

/** Stub compile-only 1.21.11, nom Yarn ({@code fxt}, {@code RenderTarget} côté Mojang) — voir {@code com.mojang.blaze3d.systems.RenderSystem}. */
public abstract class Framebuffer {

    private Framebuffer() {
    }

    public GpuTextureView getColorAttachmentView() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
