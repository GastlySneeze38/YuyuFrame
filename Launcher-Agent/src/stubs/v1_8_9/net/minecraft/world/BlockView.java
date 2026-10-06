package net.minecraft.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code adq}) — classe DÉCLARANTE
 * de {@code getBiome} : typer le receveur ainsi, jamais en {@code ClientWorld}.
 *
 * <p>Les huit méthodes de l'interface réelle sont abstraites. Ici, celles
 * ajoutées pour la vue figée du renderer ({@code celeritas/v1_8_9/world/
 * WorldSlice189}, qui les implémente toutes) ont un corps par défaut, pour ne
 * pas obliger les autres stubs qui implémentent l'interface à les déclarer.
 */
public interface BlockView {

    Biome getBiome(BlockPos pos);

    /** {@code adq.p(BlockPos)} — déclarée ici : typer le receveur en {@code BlockView}. */
    net.minecraft.block.BlockState getBlockState(BlockPos pos);

    /** {@code adq.s(BlockPos)} */
    default net.minecraft.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adq.b(BlockPos, I)} — lumière du ciel dans les bits 20 et plus, des blocs dans les bits 4 et plus. */
    default int getLight(BlockPos pos, int minBlockLight) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adq.d(BlockPos)} */
    default boolean isAir(BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adq.a(BlockPos, Direction)} */
    default int getStrongRedstonePower(BlockPos pos, net.minecraft.util.math.Direction direction) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adq.G()} */
    default net.minecraft.world.level.LevelGeneratorType getGeneratorType() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code adq.W()} — vrai si la zone couverte ne contient aucun bloc. */
    default boolean isEmpty() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
