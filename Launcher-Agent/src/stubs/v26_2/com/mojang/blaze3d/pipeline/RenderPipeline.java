package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

/**
 * Stub compile-only (26.2) — pipeline de rendu, construit par
 * {@code Blaze3DGpu262} en recopiant l'état d'un pipeline vanilla de
 * référence. Règles de l'unité : voir {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Écarts avec la 26.1.2, vérifiés par javap sur le jar client 26.2 :
 * <ul>
 *   <li>samplers et uniformes ne se déclarent plus sur le builder
 *       ({@code withSampler}/{@code withUniform} retirés) mais dans des
 *       {@link BindGroupLayout} ({@code withBindGroupLayout}) ;</li>
 *   <li>{@code withVertexFormat(format, mode)} devient
 *       {@code withVertexBinding(0, format)} + {@code withPrimitiveTopology} ;
 *       côté lecture, {@code getVertexFormat()}/{@code getVertexFormatMode()}
 *       deviennent {@code getVertexFormatBinding(int)}/{@code getPrimitiveTopology()}.</li>
 * </ul>
 */
public abstract class RenderPipeline {

    public VertexFormat getVertexFormatBinding(int binding) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public PrimitiveTopology getPrimitiveTopology() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ColorTargetState getColorTargetState() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public DepthStencilState getDepthStencilState() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isCull() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Variadique en jeu ; on l'appelle toujours sans fragment pré-assemblé. */
    public static Builder builder(Snippet... snippets) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code RenderPipeline$Snippet} — opaque, seulement le type du tableau vide passé à {@link #builder}. */
    public static final class Snippet {

        private Snippet() {
        }
    }

    /**
     * {@code RenderPipeline$Builder}. Seules les méthodes réellement employées
     * sont déclarées : chaque surcharge omise est une surcharge qu'on ne peut
     * pas appeler par erreur.
     */
    public static final class Builder {

        private Builder() {
        }

        public Builder withLocation(Identifier location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexShader(Identifier id) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withFragmentShader(Identifier id) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withBindGroupLayout(BindGroupLayout layout) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexBinding(int binding, VertexFormat format) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withPrimitiveTopology(PrimitiveTopology topology) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withColorTargetState(ColorTargetState state) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        /**
         * Surcharge NON-{@code Optional} : on ne pose rien quand l'état de
         * référence est nul (voir le stub 26.1 — {@code Optional.of(null)}
         * lèverait un NPE).
         */
        public Builder withDepthStencilState(DepthStencilState state) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withCull(boolean cull) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public RenderPipeline build() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
