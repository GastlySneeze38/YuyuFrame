package com.yuyuframe.launcheragent.celeritas.chunk.vertex.builder;

import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexEncoder;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.SortState;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.TranslucentQuadRecorder;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.impl.VanillaLikeChunkVertex;
import com.yuyuframe.launcheragent.celeritas.nativecore.NativeSort;
import org.jetbrains.annotations.Nullable;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;
import java.nio.ByteBuffer;

public class ChunkMeshBufferBuilder {
    private final ChunkVertexEncoder encoder;
    private final int stride;

    private final int initialCapacity;
    private final TranslucentQuadRecorder analyzer;

    private ByteBuffer buffer;
    private int count;
    private int capacity;
    private int sectionIndex;

    public ChunkMeshBufferBuilder(ChunkVertexEncoder encoder, int stride, int initialCapacity, boolean collectSortState) {
        this.encoder = encoder;
        this.stride = stride;

        this.buffer = null;

        this.capacity = 0;
        this.initialCapacity = initialCapacity;

        this.analyzer = collectSortState ? new TranslucentQuadRecorder() : null;
    }

    public void push(ChunkVertexEncoder.Vertex[] vertices, Material material) {
        if (this.encoder.supportsBilinearCorrection()) {
            postprocessVertices(vertices);
        }

        var vertexStart = this.count * this.stride;
        var vertexSize = vertices.length * this.stride;

        if (vertexStart + vertexSize >= this.capacity) {
            this.grow(vertexSize);
        }

        long ptr = LWJGL.memAddress(this.buffer, vertexStart);

        if (this.analyzer != null && this.capturesForJavaSort()) {
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                this.analyzer.capture(vertex);
            }
            this.capturedQuads += vertices.length / 4;
        }

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            ptr = this.encoder.write(ptr, material, vertex, this.sectionIndex);
        }

        this.count += vertices.length;
    }

    /**
     * YuyuFrame : réserve la place de {@code vertexCount} sommets à la suite de
     * ceux déjà écrits et rend l'adresse où les écrire. C'est le noyau natif du
     * maillage qui les écrit, au format de l'encodeur. Dans une passe triée,
     * appeler ensuite {@link #captureReserved} : le tri a besoin de leurs positions.
     */
    public long reserve(int vertexCount) {
        var vertexStart = this.count * this.stride;
        var vertexSize = vertexCount * this.stride;

        if (vertexStart + vertexSize >= this.capacity) {
            this.grow(vertexSize);
        }

        this.count += vertexCount;

        return LWJGL.memAddress(this.buffer, vertexStart);
    }

    /**
     * YuyuFrame : relève pour le tri les sommets écrits dans une place
     * réservée par {@link #reserve}, comme {@link #push} le fait pour les siens.
     * Sans effet hors d'une passe triée. Suppose le format « vanilla-like » :
     * la position en trois flottants au début du sommet.
     */
    public void captureReserved(int firstVertex, int vertexCount) {
        if (this.analyzer == null || !this.capturesForJavaSort()) {
            return;
        }
        this.captureRange(firstVertex, vertexCount);
    }

    private void captureRange(int firstVertex, int vertexCount) {
        this.capturedQuads += vertexCount / 4;

        var vertex = this.captureScratch;
        long ptr = LWJGL.memAddress(this.buffer, firstVertex * this.stride);

        for (int i = 0; i < vertexCount; i++) {
            vertex.x = LWJGL.memGetFloat(ptr);
            vertex.y = LWJGL.memGetFloat(ptr + 4);
            vertex.z = LWJGL.memGetFloat(ptr + 8);
            this.analyzer.capture(vertex);
            ptr += this.stride;
        }
    }

    private final ChunkVertexEncoder.Vertex captureScratch = new ChunkVertexEncoder.Vertex();

    private static void postprocessVertices(ChunkVertexEncoder.Vertex[] vertices) {
        if (vertices.length != 4) {
            for (var vertex : vertices) {
                vertex.rdhFactor = 0;
            }
            return;
        }

        int color0 = vertices[0].color;
        int color1 = vertices[1].color;
        int color2 = vertices[2].color;
        int color3 = vertices[3].color;
        // TODO can likely do SWAR tricks if performance is an issue
        int factor = encodeBilinearCorrection(color3, color1, color0, color2, 0)
                | encodeBilinearCorrection(color3, color1, color0, color2, 8) << 8
                | encodeBilinearCorrection(color3, color1, color0, color2, 16) << 16
                | encodeBilinearCorrection(color3, color1, color0, color2, 24) << 24;

        for (var vertex : vertices) {
            vertex.rdhFactor = factor;
        }
    }

    private static int encodeBilinearCorrection(int positive0, int positive1, int negative0, int negative1, int shift) {
        int factor = ((positive0 >> shift) & 0xFF) + ((positive1 >> shift) & 0xFF)
                - ((negative0 >> shift) & 0xFF) - ((negative1 >> shift) & 0xFF);
        return Math.round(factor * (127.0F / 510.0F)) & 0xFF;
    }

    private void grow(int bytesNeeded) {
        // Grow by a factor of 2, or by however many bytes more we need, whichever is larger.
        int newCapacity = Math.max(this.capacity * 2, this.capacity + bytesNeeded);
        // Ensure we allocate at least initialCapacity bytes
        newCapacity = Math.max(newCapacity, this.initialCapacity);

        this.buffer = LWJGL.memRealloc(this.buffer, newCapacity);
        this.capacity = newCapacity;
    }

    public void start(int sectionIndex) {
        this.count = 0;
        this.sectionIndex = sectionIndex;
        this.capturedQuads = 0;
        if(this.analyzer != null) {
            this.analyzer.clear();
        }
    }

    @Nullable
    public SortState getSortState() {
        if (this.analyzer == null) {
            return null;
        }
        if (!this.sortsNatively()) {
            return this.analyzer.getSortState();
        }

        // YuyuFrame : le noyau natif relit les positions dans le tampon lui-même
        // et choisit le tri. Le temps du contrôle (NativeSort.probing), le tri
        // Java est fait aussi, sur les quads relevés au fil de l'eau, et comparé.
        boolean captured = this.capturedQuads == this.count / 4 && this.count > 0;
        SortState nativeState = this.count == 0 ? SortState.NONE
                : NativeSort.analyze(LWJGL.memAddress(this.buffer, 0), this.count / 4, this.stride);

        if (nativeState == null) {
            // Le noyau a échoué : on relève tout le tampon et on trie en Java.
            if (!captured) {
                this.analyzer.clear();
                this.capturedQuads = 0;
                this.captureRange(0, this.count);
            }
            return this.analyzer.getSortState();
        }
        if (captured && NativeSort.probing()) {
            NativeSort.probe(this.analyzer.getSortState(), nativeState, this.count / 4);
        }
        return nativeState;
    }

    /** Sommets en flottants, la position en tête : le seul format que le tri natif sait relire. */
    private boolean sortsNatively() {
        return this.stride == VanillaLikeChunkVertex.STRIDE && NativeSort.available();
    }

    /** Vrai s'il faut relever les quads pour le tri Java : pas de tri natif, ou le temps de son contrôle. */
    private boolean capturesForJavaSort() {
        if (this.sortsNatively() && !NativeSort.probing()) {
            return false;
        }
        return true;
    }

    /** Quads relevés pour le tri Java depuis {@link #start} : s'il en manque, le tri Java n'a pas tout vu. */
    private int capturedQuads;

    public void resetSortState() {
        this.capturedQuads = 0;
        if (this.analyzer != null) {
            this.analyzer.clear();
        }
    }

    public void destroy() {
        if (this.buffer != null) {
            LWJGL.memFree(this.buffer);
        }

        this.buffer = null;
        this.capacity = 0;

        this.resetSortState();
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public ByteBuffer slice() {
        if (this.isEmpty()) {
            throw new IllegalStateException("No vertex data in buffer");
        }

        return LWJGL.memSlice(this.buffer, 0, this.stride * this.count);
    }

    public int count() {
        return this.count;
    }
}
