package com.mojang.renderpearl.api.pipeline;

import net.minecraft.resources.Identifier;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : n'est plus une interface fonctionnelle. {@code get} devient
 * {@code getShader}, et s'ajoutent {@code getInclude} (résolution des
 * {@code #moj_import} par le compilateur GLSL → SPIR-V) et {@code close}
 * (l'interface étend {@code AutoCloseable}). Relevé par javap.
 */
public interface ShaderSource extends AutoCloseable {

    String getShader(Identifier id, ShaderType type);

    CachedIncludeSource getInclude(Identifier id);

    @Override
    void close();

    /** {@code ShaderSource$CachedIncludeSource} — record natif opaque. */
    final class CachedIncludeSource {
        private CachedIncludeSource() {
        }

        public static CachedIncludeSource createError(String message) {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
