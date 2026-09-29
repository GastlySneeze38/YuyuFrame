package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.shaders.UniformType;

/**
 * Stub compile-only (26.2) — groupe de liaisons d'une pipeline (samplers et
 * blocs d'uniformes). En 26.2 une pipeline ne déclare plus ses samplers ni
 * ses uniformes elle-même ({@code withSampler}/{@code withUniform} ont quitté
 * {@code RenderPipeline.Builder}) : elle reçoit un ou plusieurs groupes.
 * Signatures vérifiées par javap sur le jar client 26.2.
 */
public class BindGroupLayout {
    private BindGroupLayout() {
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static class Builder {
        private Builder() {
        }

        public Builder withSampler(String name) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withUniform(String name, UniformType type) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public BindGroupLayout build() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
