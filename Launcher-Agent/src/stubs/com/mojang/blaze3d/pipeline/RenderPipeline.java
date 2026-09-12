package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

/**
 * Stub compile-only (26.1+) — pipeline de rendu.
 *
 * <p>À l'origine un simple marqueur, juste assez pour typer
 * {@code RenderPipelines.GUI_TEXTURED} (voir {@code RenderPipelinesAccessor261})
 * et les paramètres de {@code GuiGraphicsExtractor.blit/blitSprite}. Étendu
 * pour {@code Blaze3DGpu261}, qui CONSTRUIT nos pipelines maison en recopiant
 * l'état d'un pipeline vanilla de référence — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 */
public abstract class RenderPipeline {

    public VertexFormat getVertexFormat() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public VertexFormat.Mode getVertexFormatMode() {
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
     * pas appeler par erreur (leçon {@code drawItem}, où une résolution par nom
     * seul avait choisi la mauvaise).
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

        public Builder withSampler(String name) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withUniform(String name, UniformType type) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexFormat(VertexFormat format, VertexFormat.Mode mode) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withColorTargetState(ColorTargetState state) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        /**
         * Surcharge NON-{@code Optional} : la variante {@code Optional} existe
         * aussi en jeu, mais {@code GUI_TEXT} a un {@code DepthStencilState}
         * nul et lui passer {@code Optional.of(null)} lèverait un NPE. On ne
         * pose donc rien quand l'état de référence est nul.
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
