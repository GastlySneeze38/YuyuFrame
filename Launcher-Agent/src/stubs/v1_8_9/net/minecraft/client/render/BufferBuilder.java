package net.minecraft.client.render;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bfd}) — {@code BufferBuilder} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class BufferBuilder {

    protected BufferBuilder() {
    }

    /** {@code bfd.b} — {@code clear} chez Feather. */
    public void reset() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.h} — {@code getVertexCount} chez Feather. */
    public int getVertexCount() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.<init>(I)} — capacité initiale, en entiers. */
    public BufferBuilder(int capacity) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.a(I,VertexFormat)} — mode de dessin OpenGL et format des sommets. */
    public void begin(int drawMode, VertexFormat format) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.e} */
    public void end() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.f} — {@code getBuffer} chez Feather. */
    public java.nio.ByteBuffer getByteBuffer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfd.c(DDD)} — décalage ajouté à chaque sommet écrit. */
    public void offset(double x, double y, double z) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
