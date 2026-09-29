package com.mojang.renderpearl.api.pipeline;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : {@code COMBINED_IMAGE_SAMPLER} apparaît — les samplers sont des
 * uniformes comme les autres, {@code BindGroupLayout.Builder.withSampler} a
 * disparu. Constantes relevées par javap.
 */
public enum UniformType {
    COMBINED_IMAGE_SAMPLER,
    UNIFORM_BUFFER,
    TEXEL_BUFFER
}
