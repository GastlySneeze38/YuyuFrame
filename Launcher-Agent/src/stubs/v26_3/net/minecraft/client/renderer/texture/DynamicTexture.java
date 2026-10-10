package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.function.Supplier;

/**
 * Stub compile-only (26.1+) — une texture faite d'une image en mémoire. Le
 * constructeur l'envoie au GPU et garde l'image, qu'il fermera avec la texture
 * (vérifié javap, 26.1 à 26.3).
 */
public class DynamicTexture extends AbstractTexture {
    public DynamicTexture(Supplier<String> label, NativeImage pixels) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
