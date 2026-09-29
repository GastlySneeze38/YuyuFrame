package com.mojang.renderpearl.api.vertex;

import com.mojang.renderpearl.api.GpuFormat;

/**
 * Stub compile-only (26.2) — description du format de sommet d'un pipeline.
 *
 * <p>26.2 : {@code builder(int)} (le paramètre est le « step rate », 0 = un
 * élément par sommet, comme {@code DefaultVertexFormat}) et
 * {@code addAttribute(nom, GpuFormat)} remplacent {@code builder()} et
 * {@code add(nom, VertexFormatElement)} — les constantes de
 * {@code VertexFormatElement} n'existent plus. {@code VertexFormat$Mode} et
 * {@code $IndexType} sont devenus {@code com.mojang.renderpearl.api.pipeline.PrimitiveTopology}
 * et {@code IndexType}. Signatures vérifiées sur le jar client 26.2.
 */
public class VertexFormat {
    private VertexFormat() {}

    public static Builder builder(int stepRate) { return null; }

    public static class Builder {
        private Builder() {}

        public Builder addAttribute(String name, GpuFormat format) { return null; }

        public VertexFormat build() { return null; }
    }
}
