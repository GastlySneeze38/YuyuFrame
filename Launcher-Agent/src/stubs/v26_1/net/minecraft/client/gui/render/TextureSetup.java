package net.minecraft.client.gui.render;

/**
 * Stub compile-only (26.1+) — descripteur de textures d'un élément de GUI.
 *
 * <p>Ajouté le 2026-08-30 pour l'étape 1 du passage de notre rendu dans
 * l'état de GUI vanilla (voir {@code apigraphic/render/vanillagui/VanillaGuiLayer}).
 * Seul {@link #noTexture()} nous sert pour l'instant : un quad de couleur
 * pleine n'échantillonne rien.
 *
 * <p>Signature vérifiée sur {@code net/minecraft/client/gui/render/TextureSetup.class}
 * du jar client 26.1.2 : {@code public static TextureSetup noTexture()}.
 * {@code TextureSetup} y est une CLASSE (record), pas une interface — voir
 * {@code tools/audit_stubs.py}, mode de panne C.
 */
public final class TextureSetup {
    private TextureSetup() {}

    /** Aucune texture — pour un élément de couleur pleine. */
    public static TextureSetup noTexture() { return null; }

    /**
     * Une texture et son échantillonneur — utilisé pour le TEXTE, dont
     * l'atlas de police est produit par notre propre pipeline
     * ({@code Blaze3DText.ensureTexture}). Vanilla lie la texture sur
     * {@code Sampler0}, ce qu'attend justement notre shader SDF.
     */
    public static TextureSetup singleTexture(com.mojang.blaze3d.textures.GpuTextureView view,
                                             com.mojang.blaze3d.textures.GpuSampler sampler) { return null; }
}
