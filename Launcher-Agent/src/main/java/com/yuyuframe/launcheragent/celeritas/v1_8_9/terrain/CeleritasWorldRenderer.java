package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.gl.device.CommandList;
import com.yuyuframe.launcheragent.celeritas.chunk.ChunkRenderMatrices;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkMeshFormats;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.SimpleWorldRenderer;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog.GLStateManagerFogService;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.matrix.PrimitiveChunkMatrixGetter;

import java.util.List;
import java.util.Objects;

/**
 * Le renderer de terrain de la 1.8.9 : ce que le moteur ne peut pas savoir
 * seul — où est la caméra, quelle est la distance de rendu, comment dessiner
 * un block entity.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, branche « 1.8 et
 * plus ». Deux écarts : l'instance est tenue par {@link #INSTANCE} au lieu
 * d'être greffée sur le {@code WorldRenderer} du jeu (il n'y en a qu'un), et
 * le suivi de transparence des textures, vide à partir de la 1.5, a disparu.
 */
@YarnNamed
public class CeleritasWorldRenderer extends SimpleWorldRenderer<World, PrimitiveRenderSectionManager, Object, BlockEntity, Float> {

    /** L'unique renderer : le jeu n'a qu'un {@code WorldRenderer}. */
    public static final CeleritasWorldRenderer INSTANCE = new CeleritasWorldRenderer();

    private CeleritasWorldRenderer() {
    }

    /** Le monde auquel le renderer est attaché, ou {@code null}. */
    public World attachedWorld() {
        return this.world;
    }

    /**
     * Vrai si un monde est attaché. Sans type du jeu dans sa signature : c'est
     * la forme à appeler depuis une classe qui ne porte pas {@link YarnNamed}.
     */
    public boolean isAttached() {
        return this.world != null;
    }

    public static CameraState captureCameraState(double ticks) {
        Entity viewEntity = MinecraftClient.getInstance().getCameraEntity();
        Objects.requireNonNull(viewEntity, "Client must have view entity");

        double x = viewEntity.prevTickX + (viewEntity.x - viewEntity.prevTickX) * ticks;
        double y = viewEntity.prevTickY + (viewEntity.y - viewEntity.prevTickY) * ticks + (double) viewEntity.getEyeHeight();
        double z = viewEntity.prevTickZ + (viewEntity.z - viewEntity.prevTickZ) * ticks;

        float pitch = viewEntity.pitch;
        float yaw = viewEntity.yaw;
        float fogDistance = GLStateManagerFogService.INSTANCE.getFogCutoff();

        return new CameraState(x, y, z, pitch, yaw, fogDistance);
    }

    @Override
    public int getEffectiveRenderDistance() {
        return MinecraftClient.getInstance().options.viewDistance;
    }

    @Override
    public int getMinimumBuildHeight() {
        return 0;
    }

    @Override
    public int getMaximumBuildHeight() {
        return this.world.getMaxBuildHeight();
    }

    @Override
    public String getChunksDebugString() {
        return super.getChunksDebugString() + "S: " + this.renderSectionManager.getSectionsWithSkyLight().size();
    }

    @Override
    protected ChunkRenderMatrices createChunkRenderMatrices() {
        return PrimitiveChunkMatrixGetter.getMatrices();
    }

    @Override
    protected PrimitiveRenderSectionManager createRenderSectionManager(CommandList commandList) {
        return PrimitiveRenderSectionManager.create(ChunkMeshFormats.VANILLA_LIKE, this.world, this.renderDistance, commandList);
    }

    @Override
    protected void renderBlockEntityList(List<BlockEntity> list, Float partialTicksBoxed) {
        float partialTicks = partialTicksBoxed;
        for (BlockEntity blockEntity : list) {
            try {
                // -1 : pas d'étape de destruction en cours sur ce block entity.
                BlockEntityRenderDispatcher.INSTANCE.renderEntity(blockEntity, partialTicks, -1);
            } catch (RuntimeException e) {
                if (blockEntity.isRemoved()) {
                    System.err.println("Suppressing crash from invalid tile entity");
                } else {
                    throw e;
                }
            }
        }
    }
}
