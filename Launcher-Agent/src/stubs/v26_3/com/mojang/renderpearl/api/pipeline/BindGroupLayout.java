package com.mojang.renderpearl.api.pipeline;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : plus de {@code withSampler} — un sampler se déclare par
 * {@code withUniform(nom, UniformType.COMBINED_IMAGE_SAMPLER)}. Relevé par javap.
 */
public final class BindGroupLayout {
    private BindGroupLayout() {
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static class Builder {
        private Builder() {
        }

        public Builder withUniform(String name, UniformType type) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public BindGroupLayout build() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
