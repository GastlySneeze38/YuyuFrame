package net.minecraft.client.texture;

/** Stub compile-only 1.21.11, nom Yarn ({@code fyh}) — voir {@code com.mojang.blaze3d.systems.RenderSystem}. */
public final class NativeImage implements AutoCloseable {

    public NativeImage(Format format, int width, int height, boolean useStb) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Couleur ABGR packée — voir {@code Blaze3DCore.bufferedImageToNativeImage}. */
    public void setColor(int x, int y, int abgr) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code NativeImage$Format} ({@code fyh$a}), une {@code enum} en jeu — voir le stub {@code UniformType}. */
    public static final class Format {

        public static final Format RGBA;

        static {
            RGBA = stub();
        }

        private Format() {
        }

        private static Format stub() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
