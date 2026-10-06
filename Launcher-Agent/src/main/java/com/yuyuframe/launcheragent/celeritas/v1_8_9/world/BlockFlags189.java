package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.block.AbstractFluidBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;

import java.lang.foreign.MemorySegment;

/**
 * Ce que le noyau natif a besoin de savoir d'un bloc, sans pouvoir le
 * demander au jeu : des tables d'une entrée par état de bloc
 * ({@code identifiant << 4 | métadonnée}, comme dans {@link SectionSnapshot#blocks}).
 *
 * <p>C'est le deuxième format d'entrée du noyau, après les tableaux de la
 * copie figée. Elles sont remplies une fois : dans la 1.8.9 sans mods, ces
 * propriétés ne changent plus après le démarrage du jeu.
 *
 * <p>Les bits des propriétés sont ceux de {@code content-core/src/mesh/light.rs}
 * ({@code PROP_*}) ; les valeurs sont celles que lit {@code LightCache189}.
 */
@YarnNamed
public final class BlockFlags189 {

    /** Le bloc se dessine ou compte dans le volume de la section : tout sauf l'air. */
    public static final int RENDERABLE = 1;
    /** Le bloc est un cube plein opaque : il bouche la vue. */
    public static final int OPAQUE = 2;

    private static final int PROP_FULL_OPAQUE = 1 << 1;
    private static final int PROP_FULL_CUBE = 1 << 2;
    private static final int PROP_OPAQUE = 1 << 3;
    private static final int PROP_NEIGHBOUR_LIGHT = 1 << 4;
    private static final int PROP_RULE_SHIFT = 5;
    private static final int RULE_SLAB = 1;
    private static final int RULE_FLUID = 2;
    private static final int PROP_EMITTED_SHIFT = 8;
    private static final int PROP_AO_SHIFT = 12;

    private static final int STATES = 65536;

    private BlockFlags189() {
    }

    // Remplies au premier appel, par le premier fil de construction qui en a besoin.
    private static final class Holder {
        static final MemorySegment TABLE = MemorySegment.ofArray(buildFlags());
        static final MemorySegment PROPERTIES = MemorySegment.ofArray(buildProperties());
    }

    /** Un octet par état : {@link #RENDERABLE}, {@link #OPAQUE}. Pour la forme de la section. */
    public static MemorySegment table() {
        return Holder.TABLE;
    }

    /** Un entier par état : ce que le calcul de lumière lit d'un bloc. */
    public static MemorySegment properties() {
        return Holder.PROPERTIES;
    }

    private static byte[] buildFlags() {
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

    private static int[] buildProperties() {
        int[] properties = new int[STATES];
        int air = propertiesOf(Blocks.AIR);
        for (int id = 0; id < STATES; id++) {
            // Un état inconnu est lu comme de l'air, par WorldSlice189.stateAt aussi.
            Object state = Block.BLOCK_STATES.fromId(id);
            properties[id] = state == null ? air : propertiesOf(((BlockState) state).getBlock());
        }
        return properties;
    }

    /** Mêmes lectures, mêmes arrondis que {@code LightCache189.compute}. */
    private static int propertiesOf(Block block) {
        int properties = 0;
        if (block.hasTransparency()) {
            properties |= PROP_FULL_OPAQUE;
        }
        if (block.isNormalBlock()) {
            properties |= PROP_FULL_CUBE;
        }
        if (!block.isTranslucent() && block.getOpacity() == 0) {
            properties |= PROP_OPAQUE;
        }
        if (block.usesNeighbourLight()) {
            properties |= PROP_NEIGHBOUR_LIGHT;
        }
        // Les deux seules sortes de blocs dont Block.getBrightness a une règle à part (vérifié dans le jar).
        if (block instanceof AbstractFluidBlock) {
            properties |= RULE_FLUID << PROP_RULE_SHIFT;
        } else if (block instanceof SlabBlock) {
            properties |= RULE_SLAB << PROP_RULE_SHIFT;
        }
        properties |= (block.getLightLevel() & 0xF) << PROP_EMITTED_SHIFT;
        properties |= ((int) (block.getAmbientOcclusionLightLevel() * 4096.0F) & 0xFFFF) << PROP_AO_SHIFT;
        return properties;
    }
}
