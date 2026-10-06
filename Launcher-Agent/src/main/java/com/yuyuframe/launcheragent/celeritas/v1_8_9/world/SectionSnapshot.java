package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;

import java.util.HashMap;
import java.util.Map;

/**
 * La copie figée d'une section de chunk (16×16×16 blocs), prise sur le fil du
 * jeu et lue ensuite par les fils de construction — notre code, pas celui de
 * Celeritas (son adaptateur 1.12.2 garde une référence vers la section
 * vivante ; ici c'est une vraie copie).
 *
 * <p><b>Pourquoi copier</b> : le monde change pendant qu'on maille. Lire la
 * section vivante depuis un autre fil donne, au mieux, un maillage fait d'un
 * mélange d'avant et d'après.
 *
 * <p><b>Format</b> : des tableaux de types primitifs, tels que le jeu les
 * range, sans objet par bloc. C'est le premier des trois formats d'entrée du
 * futur noyau natif (identifiants de blocs, lumière) : il pourra les lire
 * tels quels.
 * <ul>
 *   <li>{@link #blocks} — 4 096 identifiants d'états, à l'indice
 *       {@code y << 8 | z << 4 | x} ; {@code null} si la section est vide ;</li>
 *   <li>{@link #blockLight}, {@link #skyLight} — 4 bits par bloc, 2 048 octets,
 *       même indice ; {@code null} si la section est vide, et pas de
 *       {@code skyLight} dans une dimension sans ciel ;</li>
 *   <li>{@link #heights} — pour une section vide, la hauteur du plus haut bloc
 *       de chaque colonne : c'est elle qui dit si le ciel éclaire ;</li>
 *   <li>{@link #biomes} — un identifiant de biome par colonne, {@code z << 4 | x}.</li>
 * </ul>
 */
@YarnNamed
public final class SectionSnapshot {

    public final int x, y, z;
    public final long capturedNanos;

    public final char[] blocks;
    public final byte[] blockLight;
    public final byte[] skyLight;
    public final int[] heights;
    public final byte[] biomes;

    /** Les block entities de la section, par position locale ({@link #localKey}) ; {@code null} s'il n'y en a pas. */
    private final HashMap<Integer, Object> blockEntities;

    private SectionSnapshot(int x, int y, int z, char[] blocks, byte[] blockLight, byte[] skyLight, int[] heights,
                            byte[] biomes, HashMap<Integer, Object> blockEntities) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.capturedNanos = System.nanoTime();
        this.blocks = blocks;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.heights = heights;
        this.biomes = biomes;
        this.blockEntities = blockEntities;
    }

    /** À appeler sur le fil du jeu. Un chunk non chargé donne une section vide. */
    public static SectionSnapshot capture(World world, int sectionX, int sectionY, int sectionZ) {
        Chunk chunk = world.getChunk(sectionX, sectionZ);

        ChunkSection section = null;
        ChunkSection[] storage = chunk.getBlockStorage();
        if (sectionY >= 0 && sectionY < storage.length) {
            section = storage[sectionY];
        }

        char[] blocks = null;
        byte[] blockLight = null;
        byte[] skyLight = null;
        int[] heights = null;

        if (section != null) {
            blocks = section.getBlockStates().clone();
            blockLight = section.getBlockLight().getValue().clone();
            ChunkNibbleArray sky = section.getSkyLight();
            if (sky != null) {
                skyLight = sky.getValue().clone();
            }
        } else {
            heights = new int[256];
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    heights[localZ << 4 | localX] = chunk.getHighestBlockY(localX, localZ);
                }
            }
        }

        byte[] biomes = chunk.getBiomeArray().clone();

        HashMap<Integer, Object> blockEntities = null;
        Map<?, ?> all = chunk.getBlockEntities();
        if (section != null && !all.isEmpty()) {
            for (Map.Entry<?, ?> entry : all.entrySet()) {
                BlockPos pos = (BlockPos) entry.getKey();
                if (pos.getY() >> 4 != sectionY) {
                    continue;
                }
                if (blockEntities == null) {
                    blockEntities = new HashMap<>();
                }
                blockEntities.put(localKey(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), entry.getValue());
            }
        }

        return new SectionSnapshot(sectionX, sectionY, sectionZ, blocks, blockLight, skyLight, heights, biomes, blockEntities);
    }

    /** Coordonnées locales, 0 à 15. */
    public static int index(int localX, int localY, int localZ) {
        return localY << 8 | localZ << 4 | localX;
    }

    private static Integer localKey(int localX, int localY, int localZ) {
        return index(localX, localY, localZ);
    }

    /** Le block entity à cette position locale, en {@code Object} : c'est l'appelant qui le type. */
    public Object blockEntity(int localX, int localY, int localZ) {
        return this.blockEntities == null ? null : this.blockEntities.get(localKey(localX, localY, localZ));
    }

    /** Lit une valeur de 4 bits, rangée comme le fait {@code ChunkNibbleArray}. */
    public static int nibble(byte[] data, int index) {
        int packed = data[index >> 1];
        return (index & 1) == 0 ? packed & 15 : packed >> 4 & 15;
    }
}
