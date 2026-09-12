package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Stub compile-only (26.1+) — juste assez pour typer le retour de
 * {@code Minecraft.getMainRenderTarget()} (voir {@code
 * GlobalUiRenderBridge261}/{@code GlobalUiPresentMixin261}, qui ciblait déjà
 * cette classe par nom de chaîne). Comparaison d'identité côté appelant
 * ("mainFramebuffer != this"), plus {@link #getColorTextureView()} pour
 * {@code Blaze3DGpu261} (vue couleur du framebuffer principal).
 */
public abstract class RenderTarget {

    public GpuTextureView getColorTextureView() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
