package com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Ses classes imbriquées sont
 * obfusquées ({@code VertexFormat$a} = IndexType, {@code VertexFormat$b} =
 * DrawMode) : stubées sous leur nom Yarn, traduites au chargement.
 *
 * <p>EXCEPTION vérifiée par {@code javap} sur le jar réel : {@code
 * VertexFormat$Builder} n'est PAS obfusquée — elle garde son nom, comme sa
 * classe conteneuse.
 */
public class VertexFormat {

    /** {@code VertexFormat$DrawMode} (Yarn), une {@code enum} en jeu — voir le stub {@code UniformType}. */
    public static final class DrawMode {

        public static final DrawMode QUADS;

        static {
            QUADS = stub();
        }

        private DrawMode() {
        }

        private static DrawMode stub() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }

    /** {@code VertexFormat$IndexType} (Yarn). Opaque : on ne fait que le repasser à {@code setIndexBuffer}. */
    public static final class IndexType {

        private IndexType() {
        }
    }

    /** {@code VertexFormat$Builder} — nom réel, non obfusqué (voir la javadoc de classe). */
    public static final class Builder {

        public Builder add(String name, VertexFormatElement element) {
            throw new UnsupportedOperationException("stub compile-only");
        }

        public VertexFormat build() {
            throw new UnsupportedOperationException("stub compile-only");
        }

        private Builder() {
        }
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Privé : aucun constructeur public en jeu — un format se construit par son {@code Builder}. */
    private VertexFormat() {
    }

    public int getVertexSize() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBuffer uploadImmediateVertexBuffer(ByteBuffer data) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBuffer uploadImmediateIndexBuffer(ByteBuffer data) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
