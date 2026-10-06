package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline;

import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.buffers.ChunkModelBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.builder.ChunkMeshBufferBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkMeshFormats;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore.NativeMesh;

import java.util.Arrays;

/**
 * Les quads d'une section que le noyau natif doit écrire — notre code. Le
 * rendu de blocs y range une commande par quad à dessiner (quel quad, pour
 * quel bloc, avec quelle teinte, dans quel tampon) ; le noyau calcule leur
 * lumière et écrit leurs sommets en un seul appel, à la fin de la section.
 *
 * <p>Le format d'une commande (quatre entiers) est celui de {@code Command}
 * dans {@code content-core/src/mesh/emit.rs}.
 *
 * <p>Un « tampon » est le couple (passe de rendu, face de la normale) : sept
 * par passe. Une passe triée (le translucide) n'utilise que le septième, « sans
 * face » ; une fois écrits, ses quads sont relevés pour le tri
 * ({@code ChunkMeshBufferBuilder.captureReserved}).
 *
 * <p>Une commande peut aussi être un bloc de fluide ({@link #addFluid}).
 *
 * <p>Un exemplaire par fil de construction.
 */
public final class QuadCommands {

    private static final int WORDS_PER_COMMAND = 4;
    private static final int FACINGS = ModelQuadFacing.COUNT;
    private static final int MAX_PASSES = NativeMesh.MAX_BUCKETS / FACINGS;

    // Bits du troisième entier d'une commande, après la position du bloc (12 bits).
    public static final int CULL_SHIFT = 12;
    public static final int SMOOTH = 1 << 15;
    /** Dans le champ de la face : la commande est un bloc de fluide. SMOOTH dit alors « passe triée ». */
    private static final int FLUID = 7;
    public static final int OFFSET_SHIFT = 16;
    public static final int BUCKET_SHIFT = 18;
    public static final int MATERIAL_SHIFT = 23;

    private int[] words = new int[WORDS_PER_COMMAND * 2048];
    private int count;
    private int fluids;

    private final TerrainRenderPass[] passes = new TerrainRenderPass[MAX_PASSES];
    private final ChunkModelBuilder[] builders = new ChunkModelBuilder[MAX_PASSES];
    private int passCount;
    /** Les passes que le noyau ne sait pas écrire, pour ne pas reposer la question à chaque bloc. */
    private TerrainRenderPass refusedPass;

    private final int[] bucketQuads = new int[NativeMesh.MAX_BUCKETS];
    private final long[] destinations = new long[NativeMesh.MAX_BUCKETS];

    /** Avant chaque section. */
    public void reset() {
        this.count = 0;
        this.fluids = 0;
        Arrays.fill(this.passes, 0, this.passCount, null);
        Arrays.fill(this.builders, 0, this.passCount, null);
        this.passCount = 0;
        this.refusedPass = null;
    }

    /**
     * Le premier tampon de la passe de ce matériau (il y en a sept, un par
     * face de normale), ou −1 si ses quads doivent rester au Java.
     */
    public int firstBucket(Material material, ChunkBuildBuffers buffers) {
        TerrainRenderPass pass = material.pass;
        for (int i = 0; i < this.passCount; i++) {
            if (this.passes[i] == pass) {
                return i * FACINGS;
            }
        }
        if (pass == this.refusedPass) {
            return -1;
        }
        // Le noyau n'écrit que le format « vanilla-like ».
        if (this.passCount == MAX_PASSES
            || buffers.getRenderPassConfiguration().getVertexTypeForPass(pass) != ChunkMeshFormats.VANILLA_LIKE) {
            this.refusedPass = pass;
            return -1;
        }
        this.passes[this.passCount] = pass;
        this.builders[this.passCount] = buffers.get(pass);
        return this.passCount++ * FACINGS;
    }

    /**
     * Vrai si la passe de ce tampon est triée (le translucide) : tous ses
     * quads vont alors dans son tampon « sans face », le septième, quelle
     * que soit leur normale.
     */
    public boolean isSorted(int firstBucket) {
        return this.passes[firstBucket / FACINGS].isSorted();
    }

    /** Vrai pour les six premiers rangs d'une passe triée : rien n'y est jamais commandé. */
    public boolean isUnusedBucket(int bucket) {
        return bucket % FACINGS != ModelQuadFacing.UNASSIGNED.ordinal() && this.passes[bucket / FACINGS].isSorted();
    }

    /** Le tampon d'un quad de cette passe dont la normale regarde vers {@code normalFace}. */
    public int bucketOf(int firstBucket, ModelQuadFacing normalFace) {
        return firstBucket + (this.isSorted(firstBucket) ? ModelQuadFacing.UNASSIGNED.ordinal() : normalFace.ordinal());
    }

    /**
     * Un bloc de fluide : le noyau fait tout, de ses faces à ses sommets. Il
     * ne lui manque que la teinte du bloc (rouge, vert, bleu), qui dépend du biome.
     */
    public void addFluid(int localX, int localY, int localZ, int firstBucket, int materialBits, int tint) {
        int packed = localX | localY << 4 | localZ << 8
            | FLUID << CULL_SHIFT
            | (this.isSorted(firstBucket) ? SMOOTH : 0)
            | firstBucket << BUCKET_SHIFT
            | (materialBits & 0xFF) << MATERIAL_SHIFT;
        this.add(0L, packed, tint);
        this.fluids++;
    }

    /** Vrai si la section a au moins un bloc de fluide commandé. */
    public boolean hasFluids() {
        return this.fluids > 0;
    }

    public void add(long quadAddress, int packed, int tint) {
        int at = this.count * WORDS_PER_COMMAND;
        if (at == this.words.length) {
            this.words = Arrays.copyOf(this.words, this.words.length * 2);
        }
        this.words[at] = (int) quadAddress;
        this.words[at + 1] = (int) (quadAddress >>> 32);
        this.words[at + 2] = packed;
        this.words[at + 3] = tint;
        this.count++;
    }

    public int count() {
        return this.count;
    }

    /** Oublie les commandes ajoutées après la {@code mark}-ième : le bloc en cours part sur un autre chemin. */
    public void truncate(int mark) {
        this.count = mark;
    }

    public int[] words() {
        return this.words;
    }

    public int bucketCount() {
        return this.passCount * FACINGS;
    }

    /** Le nombre de quads de chaque tampon, à faire remplir par le noyau ({@code NativeMesh.prepare}). */
    public int[] bucketQuads() {
        return this.bucketQuads;
    }

    public ChunkMeshBufferBuilder vertexBuffer(int bucket) {
        return this.builders[bucket / FACINGS].getVertexBuffer(ModelQuadFacing.VALUES[bucket % FACINGS]);
    }

    /** Le tableau des adresses de destination, à remplir par l'appelant : une par tampon. */
    public long[] destinations() {
        return this.destinations;
    }
}
