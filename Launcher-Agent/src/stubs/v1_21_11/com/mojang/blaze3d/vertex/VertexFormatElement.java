package com.mojang.blaze3d.vertex;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Classe {@code com.mojang} de premier niveau : NON renommée en jeu, ses
 * membres non plus (vérifié par {@code javap} sur le jar client 1.21.11 réel —
 * les constantes ci-dessous y portent ces noms exacts). Contrairement à la
 * 26.1.2, aucun accessor Mixin n'est donc nécessaire pour les atteindre.
 *
 * <p>Constantes de type OBJET : aucun risque d'inlining par javac (la règle de
 * l'unité ne vise que les primitifs).
 */
public final class VertexFormatElement {

    public static final VertexFormatElement POSITION;
    public static final VertexFormatElement COLOR;
    public static final VertexFormatElement UV0;
    public static final VertexFormatElement UV1;
    public static final VertexFormatElement UV2;

    static {
        POSITION = stub();
        COLOR = stub();
        UV0 = stub();
        UV1 = stub();
        UV2 = stub();
    }

    private VertexFormatElement() {
    }

    private static VertexFormatElement stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
