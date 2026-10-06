package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.lang.foreign.MemorySegment;

/**
 * Ce que le noyau natif a besoin de savoir d'un bloc, sans pouvoir le
 * demander au jeu : une table d'un octet par état de bloc
 * ({@code identifiant << 4 | métadonnée}, comme dans {@link SectionSnapshot#blocks}).
 *
 * <p>C'est le deuxième format d'entrée du noyau, après les tableaux de la
 * copie figée. Elle est remplie une fois : dans la 1.8.9 sans mods, ces
 * propriétés ne changent plus après le démarrage du jeu.
 */
@YarnNamed
public final class BlockFlags189 {

    /** Le bloc se dessine ou compte dans le volume de la section : tout sauf l'air. */
    public static final int RENDERABLE = 1;
    /** Le bloc est un cube plein opaque : il bouche la vue. */
    public static final int OPAQUE = 2;

    private static final int STATES = 65536;

    private BlockFlags189() {
    }

    // Remplie au premier appel, par le premier fil de construction qui en a besoin.
    private static final class Holder {
        static final MemorySegment TABLE = MemorySegment.ofArray(build());
    }

    public static MemorySegment table() {
        return Holder.TABLE;
    }

    private static byte[] build() {
        byte[] flags = new byte[STATES];
        for (int id = 0; id < STATES; id++) {
            // Un état inconnu reste à zéro : WorldSlice189.stateAt le lit comme de l'air.
            Object state = Block.BLOCK_STATES.fromId(id);
            if (state == null) {
                continue;
            }
            Block block = ((BlockState) state).getBlock();
            if (block == Blocks.AIR) {
                continue;
            }
            // « hasTransparency » chez Yarn legacy désigne le cube opaque (afh.c).
            flags[id] = (byte) (block.hasTransparency() ? RENDERABLE | OPAQUE : RENDERABLE);
        }
        return flags;
    }
}
