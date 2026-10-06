package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.light;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.model.light.data.LightDataAccess;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.WorldSlice189;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/**
 * Ce que le calcul de lumière du moteur a besoin de savoir d'un bloc, rangé
 * dans un seul entier et gardé pour toute la construction d'une section : sa
 * lumière (ciel, blocs, émise), s'il est opaque, s'il est un cube plein, et de
 * combien il assombrit l'occlusion ambiante.
 *
 * <p>Repris de l'adaptateur 1.12.2 de Celeritas ({@code LightDataCache}),
 * avec les méthodes de {@code Block} de la 1.8.9.
 */
@YarnNamed
public final class LightCache189 extends LightDataAccess {

    private final WorldSlice189 slice;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    public LightCache189(WorldSlice189 slice) {
        this.slice = slice;
    }

    @Override
    protected int compute(int x, int y, int z) {
        Block block = this.slice.stateAt(x, y, z).getBlock();

        // « hasTransparency » chez Yarn legacy désigne le cube opaque (afh.c).
        boolean fullOpaque = block.hasTransparency();
        boolean fullCube = block.isNormalBlock();
        boolean opaque = !block.isTranslucent() && block.getOpacity() == 0;
        int emitted = block.getLightLevel();

        int blockLight;
        int skyLight;
        if (fullOpaque && emitted == 0) {
            // Rien ne se dessine à l'intérieur d'un cube opaque : sa lumière ne sert pas.
            blockLight = 0;
            skyLight = 0;
        } else {
            int packed = block.getBrightness(this.slice, this.pos.setPosition(x, y, z));
            blockLight = unpackBlock(packed);
            skyLight = unpackSky(packed);
        }

        float ambientOcclusion = emitted == 0 ? block.getAmbientOcclusionLightLevel() : 1.0F;

        // Pas de bloc « toujours en pleine lumière » dans la 1.8.9 : le bit EM reste à zéro.
        return packFC(fullCube) | packFO(fullOpaque) | packOP(opaque) | packAO(ambientOcclusion)
            | packLU(emitted) | packSL(skyLight) | packBL(blockLight);
    }
}
