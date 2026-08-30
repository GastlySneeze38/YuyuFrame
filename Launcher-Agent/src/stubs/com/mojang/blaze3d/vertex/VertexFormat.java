package com.mojang.blaze3d.vertex;

/**
 * Stub compile-only (26.1+) — description du format de sommet d'un pipeline.
 *
 * <p>Ajouté le 2026-08-30 (étape 1c) : {@code VertexFormat.builder()} est
 * PUBLIC, on peut donc déclarer notre propre format au lieu d'emprunter celui
 * d'un pipeline vanilla. Les noms passés à {@link Builder#add} sont les noms
 * d'attributs vus par le GLSL ({@code Position}, {@code Color}, {@code UV0}…).
 *
 * <p>Signatures vérifiées sur le jar client 26.1.2.
 */
public final class VertexFormat {
    private VertexFormat() {}

    public static Builder builder() { return null; }

    public static final class Builder {
        private Builder() {}

        public Builder add(String name, VertexFormatElement element) { return null; }

        public Builder padding(int bytes) { return null; }

        public VertexFormat build() { return null; }
    }
}
