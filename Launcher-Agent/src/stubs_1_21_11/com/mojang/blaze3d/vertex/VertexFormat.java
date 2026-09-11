package com.mojang.blaze3d.vertex;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Ses classes imbriquées
 * ({@code VertexFormat$a} = IndexType, {@code VertexFormat$b} = DrawMode) sont
 * obfusquées : volontairement absentes.
 */
public class VertexFormat {

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
