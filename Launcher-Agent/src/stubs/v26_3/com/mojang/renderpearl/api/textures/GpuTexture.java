package com.mojang.renderpearl.api.textures;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : INTERFACE (classe abstraite en 26.2). Constantes d'usage
 * initialisées par un appel — jamais une valeur littérale, que javac
 * inlinerait (règle de l'unité).
 */
public interface GpuTexture extends AutoCloseable {

    int USAGE_COPY_DST = Stub.value();
    int USAGE_TEXTURE_BINDING = Stub.value();
    int USAGE_RENDER_ATTACHMENT = Stub.value();

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
