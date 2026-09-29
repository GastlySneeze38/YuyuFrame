package com.mojang.renderpearl.api.buffers;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : INTERFACE (classe abstraite en 26.2) ; {@code slice()} et
 * {@code slice(long, long)} y sont des méthodes {@code default}. Les
 * constantes d'usage sont initialisées par un appel — jamais une valeur
 * littérale, que javac inlinerait (règle de l'unité).
 */
public interface GpuBuffer extends AutoCloseable {

    int USAGE_COPY_DST = Stub.value();
    int USAGE_VERTEX = Stub.value();
    int USAGE_UNIFORM = Stub.value();

    default GpuBufferSlice slice(long offset, long length) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    default GpuBufferSlice slice() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    void close();

    /** Initialiseur non constant des champs ci-dessus — n'existe pas dans le jeu. */
    final class Stub {
        private Stub() {
        }

        static int value() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
