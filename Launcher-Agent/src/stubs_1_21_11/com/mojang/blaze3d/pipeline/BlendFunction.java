package com.mojang.blaze3d.pipeline;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. {@code record} dans le jeu,
 * classe finale ici. Seules les fonctions prédéfinies sont exposées : les
 * constructeurs prennent {@code SourceFactor}/{@code DestFactor}, pas encore
 * nécessaires (à ajouter depuis javap le jour où un mode sur mesure le sera).
 */
public final class BlendFunction {

    public static final BlendFunction LIGHTNING;
    public static final BlendFunction GLINT;
    public static final BlendFunction OVERLAY;
    public static final BlendFunction TRANSLUCENT;
    public static final BlendFunction TRANSLUCENT_PREMULTIPLIED_ALPHA;
    public static final BlendFunction ADDITIVE;
    public static final BlendFunction ENTITY_OUTLINE_BLIT;
    public static final BlendFunction INVERT;

    static {
        LIGHTNING = stub();
        GLINT = stub();
        OVERLAY = stub();
        TRANSLUCENT = stub();
        TRANSLUCENT_PREMULTIPLIED_ALPHA = stub();
        ADDITIVE = stub();
        ENTITY_OUTLINE_BLIT = stub();
        INVERT = stub();
    }

    /** Privé : les vrais constructeurs prennent {@code SourceFactor}/{@code DestFactor}, non exposés ici. */
    private BlendFunction() {
    }

    private static BlendFunction stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
