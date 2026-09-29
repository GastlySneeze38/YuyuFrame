package com.mojang.blaze3d.vertex;

/**
 * Stub compile-only (26.1+) — un attribut de sommet. On n'utilise que les
 * constantes prédéfinies : elles portent le type et le nombre de composantes
 * réels, qu'on n'a donc pas à redéclarer.
 *
 * <p>Ajouté le 2026-08-30 pour construire un {@link VertexFormat} maison
 * (étape 1c de la refonte du rendu) : le format non texturé de vanilla
 * (Position + Color) ne laisse aucun attribut libre pour les paramètres d'un
 * SDF de coin arrondi.
 *
 * <p>Champs vérifiés sur le jar client 26.1.2. Types réels utiles ici :
 * {@code UV0} = 2 flottants, {@code UV1}/{@code UV2} = 2 entiers courts.
 */
public final class VertexFormatElement {
    private VertexFormatElement() {}

    public static final VertexFormatElement POSITION = null;
    public static final VertexFormatElement COLOR = null;
    public static final VertexFormatElement UV0 = null;
    public static final VertexFormatElement UV1 = null;
    public static final VertexFormatElement UV2 = null;
    public static final VertexFormatElement NORMAL = null;
}
