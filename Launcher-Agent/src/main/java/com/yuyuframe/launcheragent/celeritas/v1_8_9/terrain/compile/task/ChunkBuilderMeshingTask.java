package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.task;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkCache;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderSection;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildOutput;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.tasks.ChunkBuilderTask;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltSectionMeshParts;
import com.yuyuframe.launcheragent.celeritas.chunk.occlusion.GraphDirection;
import com.yuyuframe.launcheragent.celeritas.chunk.occlusion.VisibilityEncoding;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import com.yuyuframe.launcheragent.celeritas.util.task.CancellationToken;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11C;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.PrimitiveBuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.PrimitiveChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.occlusion.ChunkOcclusionData;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.occlusion.ChunkOcclusionDataBuilder;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.util.Direction;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.ChunkRenderContext;

/**
 * Maille une section de 16×16×16 blocs sur un thread de travail : chaque bloc
 * est dessiné par le rendu de blocs VANILLA dans un tampon à part, puis ses
 * quads sont recopiés dans les tampons du moteur.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, branche « 1.8 et plus ».
 */
@YarnNamed
public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {
    private final RenderSection render;
    private final int buildTime;
    private final Vector3d camera;
    private final ChunkRenderContext renderContext;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext context, int time, Vector3d camera) {
        this.render = render;
        this.buildTime = time;
        this.camera = camera;
        this.renderContext = context;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        PrimitiveChunkBuildContext buildContext = (PrimitiveChunkBuildContext) context;
        PrimitiveBuiltRenderSectionData renderData = new PrimitiveBuiltRenderSectionData();
        ChunkOcclusionDataBuilder occluder = new ChunkOcclusionDataBuilder();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        World world = buildContext.world;
        Chunk chunk = world.getChunk(this.render.getChunkX(), this.render.getChunkZ());
        BufferBuilder tesselator = buildContext.tesselator;

        BlockPos.Mutable blockPos = new BlockPos.Mutable(minX, minY, minZ);
        // Vue figée du monde autour de la section, avec un bloc de marge.
        ChunkCache region = new ChunkCache(world, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), 1);
        BlockRenderManager renderBlocks = MinecraftClient.getInstance().getBlockRenderManager();

        // Les sommets sont écrits relativement à l'origine de la section.
        tesselator.offset(-this.render.getOriginX(), -this.render.getOriginY(), -this.render.getOriginZ());

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }
                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        blockPos.setPosition(x, y, z);
                        BlockState blockState = chunk.method_9154(blockPos);
                        Block block = blockState.getBlock();
                        if (block == Blocks.AIR) {
                            continue;
                        }

                        if (block.hasBlockEntity()) {
                            BlockEntity tileEntity = chunk.getBlockEntity(blockPos, Chunk.Status.CHECK);
                            if (BlockEntityRenderDispatcher.INSTANCE.getRenderer(tileEntity) != null) {
                                renderData.globalBlockEntities.add(tileEntity);
                            }
                        }

                        Object pass = block.getRenderLayerType();

                        tesselator.begin(GL11C.GL_QUADS, VertexFormats.BLOCK);
                        renderBlocks.renderBlock(blockState, blockPos, region, tesselator);
                        tesselator.end();
                        buildContext.copyRawBuffer(tesselator.getByteBuffer().asIntBuffer(), tesselator.getVertexCount(), buffers, buffers.getRenderPassConfiguration().getMaterialForRenderType(pass));
                        tesselator.reset();

                        // « hasTransparency » chez Yarn legacy désigne le cube opaque (afh.c).
                        if (block.hasTransparency()) {
                            occluder.markClosed(x, y, z);
                        }
                    }
                }
            }
        } finally {
            tesselator.offset(0, 0, 0);
        }

        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = BuiltSectionMeshParts.groupFromBuildBuffers(buffers, (float) (camera.x - minX), (float) (camera.y - minY), (float) (camera.z - minZ));

        if (!meshes.isEmpty()) {
            renderData.hasBlockGeometry = true;
        }

        encodeVisibilityData(occluder, renderData);

        return new ChunkBuildOutput(this.render, renderData, meshes, this.buildTime);
    }

    private static final Direction[] FACINGS = new Direction[GraphDirection.COUNT];

    static {
        FACINGS[GraphDirection.UP] = Direction.UP;
        FACINGS[GraphDirection.DOWN] = Direction.DOWN;
        FACINGS[GraphDirection.WEST] = Direction.WEST;
        FACINGS[GraphDirection.EAST] = Direction.EAST;
        FACINGS[GraphDirection.NORTH] = Direction.NORTH;
        FACINGS[GraphDirection.SOUTH] = Direction.SOUTH;
    }

    private static void encodeVisibilityData(ChunkOcclusionDataBuilder occluder, BuiltRenderSectionData renderData) {
        ChunkOcclusionData data = occluder.build();
        renderData.visibilityData = VisibilityEncoding.encode((from, to) -> data.isVisibleThrough(FACINGS[from], FACINGS[to]));
    }
}
