package net.minecraft.client.texture;

import java.util.function.Supplier;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code ilc}) — une texture faite d'une
 * image en mémoire. Le constructeur l'envoie au GPU et garde l'image, qu'il
 * fermera avec la texture. Pendant de {@code DynamicTexture} en 26.1.2.
 */
public class NativeImageBackedTexture extends AbstractTexture {

    public NativeImageBackedTexture(Supplier<String> nameSupplier, NativeImage image) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
