package com.mojang.blaze3d.pipeline;

/**
 * Stub compile-only (26.1+) — juste assez pour typer le retour de
 * {@code Minecraft.getMainRenderTarget()} (voir {@code
 * GlobalUiRenderBridge261}/{@code GlobalUiPresentMixin261}, qui ciblait déjà
 * cette classe par nom de chaîne). Comparaison d'identité uniquement
 * ("mainFramebuffer != this") côté appelant, jamais de membre lu ici.
 */
public abstract class RenderTarget {
}
