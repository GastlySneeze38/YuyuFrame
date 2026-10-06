package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.world.chunk.Chunk;

import java.lang.foreign.MemorySegment;

/**
 * Ce qu'une colonne de chunk a de commun à toutes ses sections, copié une
 * fois et partagé par leurs copies figées ({@link SectionSnapshot}) — notre code.
 *
 * <p>Avant, chaque section copiait ses biomes, et chaque section vide relevait
 * la hauteur des 256 colonnes de blocs : pour une colonne de chunk dont onze
 * sections sur seize sont de l'air, onze fois le même relevé. À grande
 * distance de rendu, c'est ce qui remplissait la mémoire.
 *
 * <p>Fil du jeu seulement pour la copie ; lu ensuite par les fils de construction.
 */
@YarnNamed
public final class ColumnSnapshot {

    public final long capturedNanos = System.nanoTime();

    /** Un identifiant de biome par colonne de blocs, {@code z << 4 | x}. */
    public final byte[] biomes;

    // Les hauteurs ne servent qu'aux sections vides (elles disent où le ciel
    // éclaire) : relevées à la première qui les demande.
    private int[] heights;
    private MemorySegment heightsSegment;

    public ColumnSnapshot(Chunk chunk) {
        this.biomes = chunk.getBiomeArray().clone();
    }

    /** La hauteur du plus haut bloc de chaque colonne de blocs. À appeler sur le fil du jeu. */
    public int[] heights(Chunk chunk) {
        if (this.heights == null) {
            int[] heights = new int[256];
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    heights[localZ << 4 | localX] = chunk.getHighestBlockY(localX, localZ);
                }
            }
            this.heights = heights;
            this.heightsSegment = MemorySegment.ofArray(heights);
        }
        return this.heights;
    }

    /** Les mêmes hauteurs, pour le noyau natif. Après {@link #heights}. */
    public MemorySegment heightsSegment() {
        return this.heightsSegment;
    }
}
