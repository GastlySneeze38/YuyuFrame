package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.UniformType;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <h2>Différence avec la 26.1.2, la raison de cette unité</h2>
 *
 * La 1.21.11 n'a ni {@code ColorTargetState} ni {@code DepthStencilState} :
 * l'état couleur/profondeur se décrit par {@code withBlend}/{@code withColorWrite}/
 * {@code withColorLogic} et {@code withDepthTestFunction}/{@code withDepthWrite}/
 * {@code withDepthBias}. Et elle offre des surcharges {@code String} pour la
 * localisation et les shaders, qui évitent le type obfusqué {@code Identifier}.
 *
 * <p>Les membres à types obfusqués ({@code Identifier}, {@code UniformType},
 * {@code VertexFormat$DrawMode}) sont stubés sous leurs noms Yarn, traduits au
 * chargement. Omis car inutiles ici : {@code getLocation()/getVertexShader()/
 * getFragmentShader()}, {@code getShaderDefines()}.
 */
public class RenderPipeline {

    /** Privé : aucun constructeur public en jeu — un pipeline se construit par {@link #builder}. */
    private RenderPipeline() {
    }

    public static Builder builder(Snippet... snippets) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public DepthTestFunction getDepthTestFunction() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public PolygonMode getPolygonMode() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isCull() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public LogicOp getColorLogic() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public Optional<BlendFunction> getBlendFunction() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isWriteColor() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isWriteAlpha() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isWriteDepth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getDepthBiasScaleFactor() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getDepthBiasConstant() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public VertexFormat getVertexFormat() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public VertexFormat.DrawMode getVertexFormatMode() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public List<String> getSamplers() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code RenderPipeline$Builder} — nom réel non obfusqué en 1.21.11 (vérifié par javap). */
    public static class Builder {

        /** Privé : aucun constructeur public en jeu — s'obtient par {@link RenderPipeline#builder}. */
        private Builder() {
        }

        public Builder withLocation(String location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withLocation(Identifier location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexShader(String location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexShader(Identifier location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withFragmentShader(String location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withFragmentShader(Identifier location) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withUniform(String name, UniformType type) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withVertexFormat(VertexFormat format, VertexFormat.DrawMode mode) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withShaderDefine(String name) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withShaderDefine(String name, int value) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withShaderDefine(String name, float value) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withSampler(String sampler) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withDepthTestFunction(DepthTestFunction function) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withPolygonMode(PolygonMode mode) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withCull(boolean cull) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withBlend(BlendFunction blend) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withoutBlend() {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withColorWrite(boolean writeColor) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withColorWrite(boolean writeColor, boolean writeAlpha) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withDepthWrite(boolean writeDepth) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withColorLogic(LogicOp logic) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Builder withDepthBias(float scaleFactor, float constant) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public Snippet buildSnippet() {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public RenderPipeline build() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }

    /**
     * {@code RenderPipeline$Snippet} — {@code record} dans le jeu, classe finale
     * ici (voir {@code GpuBufferSlice}). Aucun membre : ses accesseurs exposent
     * tous des types obfusqués ou {@code Optional} de ceux-ci ; on ne s'en sert
     * que comme valeur opaque (tableau vide passé à {@link #builder}).
     */
    public static final class Snippet {

        /** Privé : le vrai constructeur prend des {@code Optional} de types obfusqués. */
        private Snippet() {
        }
    }
}
