package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.block.AbstractFluidBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.material.Material;

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
    private static final int PROP_NON_AIR = 1 << 25;
    private static final int PROP_BLOCK_ENTITY = 1 << 26;
    private static final int PROP_MATERIAL_SOLID = 1;
    private static final int PROP_BLOCKS_MOVEMENT = 1 << 7;
    private static final int PROP_FLUID_SHIFT = 28;
    private static final int FLUID_WATER = 1;
    private static final int FLUID_LAVA = 2;
    private static final int PROP_ICE = 1 << 30;
    private static final int PROP_FULL_BLOCK = 1 << 31;

    private static final int STATES = 65536;

    private BlockFlags189() {
    }

    // Remplies au premier besoin, par le premier fil de construction qui les demande.
    private static volatile MemorySegment table;
    private static volatile MemorySegment properties;

    /**
     * À chaque rechargement du renderer : une propriété au moins dépend d'un
     * réglage — les feuilles sont des cubes opaques en graphismes rapides, pas
     * en graphismes détaillés. Vanilla la change juste avant de recharger.
     */
    public static void invalidate() {
        table = null;
        properties = null;
    }

    /** Un octet par état : {@link #RENDERABLE}, {@link #OPAQUE}. Pour la forme de la section. */
    public static MemorySegment table() {
        MemorySegment current = table;
        if (current == null) {
            current = MemorySegment.ofArray(buildFlags());
            table = current;
        }
        return current;
    }

    /** Un entier par état : ce que le noyau lit d'un bloc (lumière, fluides, blocs exposés). */
    public static MemorySegment properties() {
        MemorySegment current = properties;
        if (current == null) {
            current = MemorySegment.ofArray(buildProperties());
            properties = current;
        }
        return current;
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
        // Pour le relevé des blocs exposés (content-core/src/mesh/expose.rs).
        if (block != Blocks.AIR) {
            properties |= PROP_NON_AIR;
        }
        if (block.hasBlockEntity()) {
            properties |= PROP_BLOCK_ENTITY;
        }
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
        // Pour le rendu des fluides (content-core/src/mesh/fluid.rs).
        Material material = block.getMaterial();
        if (material.isSolid()) {
            properties |= PROP_MATERIAL_SOLID;
        }
        if (material.blocksMovement()) {
            properties |= PROP_BLOCKS_MOVEMENT;
        }
        if (material == Material.WATER) {
            properties |= FLUID_WATER << PROP_FLUID_SHIFT;
        } else if (material == Material.LAVA) {
            properties |= FLUID_LAVA << PROP_FLUID_SHIFT;
        } else if (material == Material.ICE) {
            properties |= PROP_ICE;
        }
        if (block.isFullBlock()) {
            properties |= PROP_FULL_BLOCK;
        }
        properties |= (block.getLightLevel() & 0xF) << PROP_EMITTED_SHIFT;
        // Treize bits : la valeur va de 0 à 4096.
        properties |= ((int) (block.getAmbientOcclusionLightLevel() * 4096.0F) & 0x1FFF) << PROP_AO_SHIFT;
        return properties;
    }
}
