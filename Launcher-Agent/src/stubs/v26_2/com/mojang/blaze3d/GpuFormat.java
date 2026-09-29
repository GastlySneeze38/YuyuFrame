package com.mojang.blaze3d;

/**
 * Stub compile-only (26.2) — format de pixel/attribut unifié de la 26.2, qui
 * remplace {@code textures.TextureFormat} (textures) ET les constantes de
 * {@code VertexFormatElement} (attributs de sommet). Enum dans le jeu
 * (vérifié par javap) ; seules les constantes utilisées ici sont déclarées.
 *
 * <p>Les formats d'attributs de sommet ne sont pas nommés ici : ils sont lus
 * sur les constantes vanilla de {@code DefaultVertexFormat} par
 * {@code apimixin/v26_2/render/DefaultVertexFormatAccessor262}.
 */
public enum GpuFormat {
    RGBA8_UNORM
}
