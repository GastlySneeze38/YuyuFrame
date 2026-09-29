package net.minecraft.client.renderer;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Stub compile-only (26.1+) — {@code GUI_TEXTURED} : tenté en champ PUBLIC
 * direct (registre de constantes de pipelines, motif habituel des grands
 * registres vanilla type Blocks/Items — jamais confirmé PRIVÉ dans le code
 * existant, seulement accédé via le motif réflexif universel de ce projet
 * qui ne distingue pas la visibilité réelle). Si ça s'avère faux à l'usage
 * (NoSuchFieldError/IllegalAccessError), remplacer par un {@code @Accessor}
 * statique — voir {@code UiVanillaItemRenderer}/{@code UiTextBlaze3D} pour
 * l'ancien chemin réflexif équivalent.
 */
public abstract class RenderPipelines {
    public static final RenderPipeline GUI_TEXTURED = null;

    /**
     * Pipeline de TEXTE de la GUI — référence par défaut des pipelines maison
     * de {@code Blaze3DGpu261} : format de sommet, état couleur et cull en sont
     * recopiés. Champ public vérifié sur le jar client 26.1.2.
     */
    public static final RenderPipeline GUI_TEXT = null;
}
