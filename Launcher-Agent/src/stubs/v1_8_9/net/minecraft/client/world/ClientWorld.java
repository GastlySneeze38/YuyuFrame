package net.minecraft.client.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/** Stub compile-only 1.8.9, nom Yarn legacy ({@code bdb}). */
public class ClientWorld extends World {

    private ClientWorld() {
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
