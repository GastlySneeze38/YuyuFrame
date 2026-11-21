package com.mojang.blaze3d.buffers;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Opaque : produite par
 * {@code GpuBuffer.slice} ou {@code DynamicUniforms.writeTransform}, puis
 * seulement relayée à {@code writeToBuffer}/{@code setUniform}.
 */
public final class GpuBufferSlice {

    private GpuBufferSlice() {
    }

    /** Tampon lu par le CPU (javap 26.2 : record final, {@code data()} + {@code close()}). */
    public static final class MappedView implements AutoCloseable {
        private MappedView() {
        }

        public java.nio.ByteBuffer data() {
            throw new UnsupportedOperationException("stub compile-only");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
