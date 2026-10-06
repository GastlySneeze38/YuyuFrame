package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.gl.device.CommandList;
import com.yuyuframe.launcheragent.celeritas.gl.device.RenderDevice;
import com.yuyuframe.launcheragent.celeritas.chunk.DefaultChunkRenderer;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderPassConfiguration;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderSection;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderSectionManager;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildOutput;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.tasks.ChunkBuilderTask;
import com.yuyuframe.launcheragent.celeritas.chunk.fog.FogService;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.chunk.occlusion.AsyncOcclusionMode;
import com.yuyuframe.launcheragent.celeritas.chunk.shader.ChunkShaderInterface;
import com.yuyuframe.launcheragent.celeritas.chunk.shader.ChunkShaderTextureSlot;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexType;
import com.yuyuframe.launcheragent.celeritas.viewport.Viewport;
import com.yuyuframe.launcheragent.celeritas.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog.GLStateManagerFogService;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.PrimitiveBuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.PrimitiveChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.task.ChunkBuilderMeshingTask;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.ChunkRenderContext;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.SnapshotCache;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Le gestionnaire de sections de la 1.8.9 : dit au moteur comment construire
 * une section, quand elle est vide, et quels réglages d'occlusion utiliser.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, branche « 1.8 et plus ».
 */
@YarnNamed
public class PrimitiveRenderSectionManager extends RenderSectionManager {
    private final World world;

    private final ReferenceOpenHashSet<RenderSection> sectionsWithSkyLight = new ReferenceOpenHashSet<>();

    /** Les copies figées récentes des sections, partagées entre les constructions voisines. Fil du jeu seulement. */
    private final SnapshotCache snapshots;

    public PrimitiveRenderSectionManager(RenderPassConfiguration<?> configuration, World world, int renderDistance, CommandList commandList, int minSection, int maxSection, int requestedThreads) {
        super(configuration, new ContextFactory(world, configuration), ChunkRenderer::new, renderDistance, commandList, minSection, maxSection, requestedThreads);
        this.world = world;
        this.snapshots = new SnapshotCache(world);
    }

    /** Une section a changé : sa copie figée ne vaut plus rien. */
    public void invalidateSnapshot(int sectionX, int sectionY, int sectionZ) {
        this.snapshots.invalidate(sectionX, sectionY, sectionZ);
    }

    /** Un chunk entier est arrivé ou parti. */
    public void invalidateSnapshotColumn(int chunkX, int chunkZ) {
        this.snapshots.invalidateColumn(chunkX, chunkZ);
    }

    /** Un contexte de construction par thread de travail. Classe nommée : elle tient un type du jeu. */
    @YarnNamed
    private static final class ContextFactory implements Supplier<ChunkBuildContext> {
        private final World world;
        private final RenderPassConfiguration<?> configuration;

        ContextFactory(World world, RenderPassConfiguration<?> configuration) {
            this.world = world;
            this.configuration = configuration;
        }

        @Override
        public ChunkBuildContext get() {
            return new PrimitiveChunkBuildContext(this.world, this.configuration);
        }
    }

    public static PrimitiveRenderSectionManager create(ChunkVertexType vertexType, World world, int renderDistance, CommandList commandList) {
        // 0 : le moteur choisit le nombre de threads de construction.
        int idealThreadCount = 0;
        int maxSection = world.getMaxBuildHeight() / 16;
        return new PrimitiveRenderSectionManager(PrimitiveRenderPassConfigurationBuilder.build(vertexType), world, renderDistance, commandList,
                0, maxSection,
                idealThreadCount);
    }

    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return AsyncOcclusionMode.EVERYTHING;
    }

    @Override
    protected boolean shouldRespectUpdateTaskQueueSizeLimit() {
        return true;
    }

    /**
     * Allumé (2026-10-06) — l'amont le laisse éteint sur cette version. Le
     * maillage fournit les boîtes opaques dont le test a besoin
     * ({@code ChunkBuilderMeshingTask}), et le volume de vue sa matrice
     * ({@code RendererHooks189.createViewport}).
     */
    @Override
    protected boolean useRasterOcclusionCulling() {
        return true;
    }

    @Override
    public FogService getFogService() {
        return GLStateManagerFogService.INSTANCE;
    }

    @Override
    protected boolean useFogOcclusion() {
        return true;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport positionedViewport, boolean spectator) {
        var camBlockPos = positionedViewport.getBlockCoord();
        // getBlockState est déclarée par BlockView : le receveur est typé ainsi.
        BlockView view = this.world;
        Block block = view.getBlockState(new BlockPos(camBlockPos.x(), camBlockPos.y(), camBlockPos.z())).getBlock();
        // « hasTransparency » chez Yarn legacy, « isOpaqueCube » chez Feather : même méthode (afh.c).
        boolean opaque = block.hasTransparency();
        return !spectator || !opaque;
    }

    @Override
    protected boolean isSectionVisuallyEmpty(int x, int y, int z) {
        Chunk chunk = this.world.getChunk(x, z);
        return chunk.isEmpty();
    }

    private void populateTileEntities(Chunk chunk, int sectionY) {
        if (chunk.isEmpty()) {
            return;
        }

        ChunkSection[] sections = chunk.getBlockStorage();

        if (sectionY < 0 || sectionY >= sections.length) {
            return;
        }

        ChunkSection section = sections[sectionY];

        if (section == null || section.isEmpty()) {
            return;
        }

        int sectionBlockY = sectionY * 16;
        int chunkBlockX = chunk.chunkX * 16;
        int chunkBlockZ = chunk.chunkZ * 16;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = section.getBlock(x, y, z);
                    if (block.hasBlockEntity()) {
                        BlockPos pos = new BlockPos(chunkBlockX + x, sectionBlockY + y, chunkBlockZ + z);
                        chunk.getBlockEntity(pos, Chunk.Status.IMMEDIATE);
                    }
                }
            }
        }
    }

    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        if (isSectionVisuallyEmpty(render.getChunkX(), render.getChunkY(), render.getChunkZ())) {
            return null;
        }

        // Sur le thread du jeu : crée les block entities manquants, puis copie la
        // section et ses voisines. La construction, sur un autre thread, ne lira
        // que cette copie.
        populateTileEntities(this.world.getChunk(render.getChunkX(), render.getChunkZ()), render.getChunkY());

        ChunkRenderContext context = new ChunkRenderContext(
            new SectionPos(render.getChunkX(), render.getChunkY(), render.getChunkZ()),
            this.snapshots.around(render.getChunkX(), render.getChunkY(), render.getChunkZ()));

        return new ChunkBuilderMeshingTask(render, context, frame, this.cameraPosition);
    }

    @Override
    protected boolean allowImportantRebuilds() {
        return false;
    }

    @Override
    protected boolean updateSectionInfo(RenderSection render, @Nullable BuiltRenderSectionData info) {
        boolean changed = super.updateSectionInfo(render, info);
        if (changed) {
            if (!(info instanceof PrimitiveBuiltRenderSectionData data)) {
                this.sectionsWithSkyLight.remove(render);
            } else if (data.hasSkyLight) {
                this.sectionsWithSkyLight.add(render);
            }
        }
        return changed;
    }

    public Collection<RenderSection> getSectionsWithSkyLight() {
        return this.sectionsWithSkyLight;
    }

    private static class ChunkRenderer extends DefaultChunkRenderer {
        public ChunkRenderer(RenderDevice device, RenderPassConfiguration<?> renderPassConfiguration) {
            super(device, renderPassConfiguration, GLStateManagerFogService.INSTANCE);
        }

        @Override
        protected void configureShaderInterface(ChunkShaderInterface shader) {
            shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
            shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
        }
    }
}
