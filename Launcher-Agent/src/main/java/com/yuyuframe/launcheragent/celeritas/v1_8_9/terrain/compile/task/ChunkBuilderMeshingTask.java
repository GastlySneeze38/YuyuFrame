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
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.util.math.BlockPos;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderSection;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildOutput;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.tasks.ChunkBuilderTask;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltSectionMeshParts;
import com.yuyuframe.launcheragent.celeritas.chunk.occlusion.GraphDirection;
import com.yuyuframe.launcheragent.celeritas.chunk.occlusion.SectionVisibilityBuilder;
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
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.WorldSlice189;

import com.yuyuframe.launcheragent.celeritas.raster.PackedBox;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.debug.MeshStats;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore.NativeMesh;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.BlockFlags189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.FluidData189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.SectionSnapshot;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline.BlockRenderer189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline.QuadCommands;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.builder.ChunkMeshBufferBuilder;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maille une section de 16×16×16 blocs sur un fil de construction.
 *
 * <p>Depuis le 2026-10-06, vanilla ne dessine plus les blocs à modèle : c'est
 * {@code BlockRenderer189} qui le fait, en lisant la copie figée du monde
 * ({@link WorldSlice189}) et en écrivant directement au format du moteur.
 *
 * <p><b>Repli sur vanilla</b>, par le chemin d'avant (vanilla dessine le bloc
 * dans un tampon, que l'on recopie) :
 * <ul>
 *   <li>les fluides, pas encore portés — repli attendu, silencieux ;</li>
 *   <li>un bloc d'un type de rendu inconnu, ou sur lequel notre rendu a levé
 *       une exception — repli avec un avertissement bien visible, une fois
 *       par sorte de bloc ({@link #warnFallback}).</li>
 * </ul>
 * Dans les deux cas vanilla lit lui aussi la copie figée, pas le monde vivant.
 */
@YarnNamed
public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {

    /** Les types de rendu d'un bloc dans la 1.8.9 ({@code Block.getBlockType}). */
    private static final int TYPE_INVISIBLE = -1;
    private static final int TYPE_FLUID = 1;
    private static final int TYPE_BLOCK_ENTITY_ONLY = 2;
    private static final int TYPE_MODEL = 3;

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
        long taskStart = System.nanoTime();
        PrimitiveBuiltRenderSectionData renderData = new PrimitiveBuiltRenderSectionData();
        // La forme de la section (visibilité, boîtes occultantes) se calcule dans le
        // noyau natif, à partir des seuls identifiants de blocs. Sans lui, c'est le
        // Java qui la relève bloc par bloc.
        boolean nativeShape = NativeMesh.available();
        boolean javaShape = !nativeShape;
        ChunkOcclusionDataBuilder occluder = new ChunkOcclusionDataBuilder();
        // Pour le culling par rastérisation : quels blocs bouchent la vue, et
        // dans quel volume de la section il y a quelque chose à dessiner.
        SectionVisibilityBuilder rasterShape = new SectionVisibilityBuilder();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        WorldSlice189 slice = buildContext.slice;
        slice.init(this.renderContext);
        buildContext.lightCache.reset(minX, minY, minZ);
        buildContext.blockRenderer.reset();

        // Les quads des blocs à modèle : le Java décide lesquels se dessinent, le
        // noyau natif calcule leur lumière et écrit leurs sommets, en un appel à la
        // fin de la section. Le temps du contrôle (MeshStats), le Java les écrit
        // aussi, dans les vrais tampons, et le noyau à côté pour comparer.
        BlockRenderer189 blockRenderer = buildContext.blockRenderer;
        QuadCommands commands = buildContext.quadCommands;
        commands.reset();
        boolean nativeQuads = NativeMesh.quadsAvailable() && this.prepareNativeQuads(buildContext, minX, minY, minZ);
        boolean probe = nativeQuads && MeshStats.takeProbe();
        boolean vanillaFallback = false;

        BufferBuilder tesselator = buildContext.tesselator;
        BlockPos.Mutable blockPos = new BlockPos.Mutable(minX, minY, minZ);
        BlockRenderManager vanillaRenderer = MinecraftClient.getInstance().getBlockRenderManager();

        // Pour le repli : vanilla écrit ses sommets relativement à l'origine de la section.
        tesselator.offset(-minX, -minY, -minZ);

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }
                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        BlockState blockState = slice.stateAt(x, y, z);
                        Block block = blockState.getBlock();
                        if (block == Blocks.AIR) {
                            continue;
                        }
                        blockPos.setPosition(x, y, z);

                        if (block.hasBlockEntity()) {
                            BlockEntity blockEntity = slice.blockEntityAt(x, y, z);
                            BlockEntityRenderer renderer = BlockEntityRenderDispatcher.INSTANCE.getRenderer(blockEntity);
                            if (renderer != null) {
                                // Écart avec l'amont, qui les range tous en « global » (dessinés
                                // à chaque image, visibles ou non). Comme vanilla : seuls ceux qui
                                // se voient hors de leur bloc échappent au culling de leur section.
                                if (renderer.rendersOutsideBoundingBox()) {
                                    renderData.globalBlockEntities.add(blockEntity);
                                } else {
                                    renderData.culledBlockEntities.add(blockEntity);
                                }
                            }
                        }

                        int type = block.getBlockType();
                        if (type == TYPE_MODEL) {
                            int mark = commands.count();
                            try {
                                if (!nativeQuads) {
                                    blockRenderer.render(blockState, blockPos, buffers);
                                } else if (probe) {
                                    blockRenderer.emit(blockState, blockPos, buffers, commands);
                                    blockRenderer.render(blockState, blockPos, buffers);
                                } else if (!blockRenderer.emit(blockState, blockPos, buffers, commands)) {
                                    // Format de sommets que le noyau n'écrit pas : ces quads restent au Java.
                                    blockRenderer.render(blockState, blockPos, buffers);
                                }
                            } catch (Throwable failure) {
                                commands.truncate(mark);
                                vanillaFallback = true;
                                warnFallback(block, "notre rendu a levé une exception", failure);
                                this.renderWithVanilla(vanillaRenderer, blockState, blockPos, slice, tesselator, buildContext, buffers, block);
                            }
                        } else if (type == TYPE_FLUID) {
                            // L'eau et la lave : le noyau les dessine de bout en bout, comme vanilla.
                            // Le temps du contrôle, vanilla les dessine aussi, pour comparer.
                            int mark = commands.count();
                            boolean emitted = false;
                            if (nativeQuads && NATIVE_FLUIDS) {
                                try {
                                    emitted = blockRenderer.emitFluid(blockState, blockPos, buffers, commands);
                                } catch (Throwable failure) {
                                    commands.truncate(mark);
                                    vanillaFallback = true;
                                    warnFallback(block, "notre rendu de fluide a levé une exception", failure);
                                }
                            }
                            if (!emitted || probe) {
                                this.renderWithVanilla(vanillaRenderer, blockState, blockPos, slice, tesselator, buildContext, buffers, block);
                            }
                        } else if (type != TYPE_INVISIBLE && type != TYPE_BLOCK_ENTITY_ONLY) {
                            vanillaFallback = true;
                            warnFallback(block, "type de rendu inconnu : " + type, null);
                            this.renderWithVanilla(vanillaRenderer, blockState, blockPos, slice, tesselator, buildContext, buffers, block);
                        }

                        if (javaShape) {
                            rasterShape.markRenderable(x, y, z);
                            // « hasTransparency » chez Yarn legacy désigne le cube opaque (afh.c).
                            if (block.hasTransparency()) {
                                occluder.markClosed(x, y, z);
                                rasterShape.markOpaque(x, y, z);
                            }
                        }
                    }
                }
            }
        } finally {
            tesselator.offset(0, 0, 0);
            slice.release();
        }

        if (nativeQuads && commands.count() > 0) {
            if (probe) {
                if (vanillaFallback) {
                    // Un repli a écrit dans les mêmes tampons : ils ne se comparent plus un à un.
                    MeshStats.probeSkipped();
                } else {
                    this.probeNativeQuads(buildContext, commands);
                }
            } else if (!this.writeNativeQuads(buildContext, commands)) {
                // Le noyau a échoué : il est mis hors service, et la section est refaite par le Java.
                NativeMesh.disableQuads();
                LauncherLog.err("[RENDERER] le noyau natif a échoué sur l'écriture des quads — le rendu Java reprend pour toute la session");
                return this.execute(context, cancellationToken);
            }
        }

        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = BuiltSectionMeshParts.groupFromBuildBuffers(buffers, (float) (camera.x - minX), (float) (camera.y - minY), (float) (camera.z - minZ));

        if (!meshes.isEmpty()) {
            renderData.hasBlockGeometry = true;
        }

        long shapeStart = System.nanoTime();
        long shapeNanos;
        if (nativeShape) {
            // La section du milieu des 27 copies : celle que l'on maille.
            this.applyNativeShape(this.renderContext.sections()[13].blocks, renderData);
            shapeNanos = System.nanoTime() - shapeStart;
        } else {
            encodeVisibilityData(occluder, renderData);
            renderData.occluderBoxes = rasterShape.computeOccluderBoxes();
            shapeNanos = System.nanoTime() - shapeStart;
        }
        MeshStats.section(System.nanoTime() - taskStart, shapeNanos);

        return new ChunkBuildOutput(this.render, renderData, meshes, this.buildTime);
    }

    /**
     * Tend au noyau les 27 sections copiées. Faux si l'appel échoue : le noyau
     * est alors mis hors service et le Java garde la section.
     */
    private boolean prepareNativeQuads(PrimitiveChunkBuildContext buildContext, int minX, int minY, int minZ) {
        MemorySegment workspace = buildContext.nativeWorkspace;
        if (workspace == null) {
            return false;
        }
        try {
            NativeMesh.begin(workspace, minX, minY, minZ, buildContext.slice.skyLit());
            SectionSnapshot[] sections = this.renderContext.sections();
            for (int slot = 0; slot < sections.length; slot++) {
                SectionSnapshot section = sections[slot];
                if (section != null) {
                    NativeMesh.loadSection(workspace, slot, section.blocksSegment, section.blockLightSegment,
                        section.skyLightSegment, section.heightsSegment);
                }
            }
            return true;
        } catch (Throwable failure) {
            NativeMesh.disableQuads();
            LauncherLog.err("[RENDERER] le noyau natif a échoué à la préparation d'une section — le rendu Java reprend pour toute la session", failure);
            return false;
        }
    }

    /** Réserve la place des quads dans les tampons de la section et les fait écrire par le noyau. */
    private boolean writeNativeQuads(PrimitiveChunkBuildContext buildContext, QuadCommands commands) {
        int buckets = commands.bucketCount();
        int[] quads = commands.bucketQuads();
        long[] destinations = commands.destinations();
        int[] firstVertex = new int[buckets];
        try {
            // Le noyau compte d'abord : le nombre de quads d'un fluide ne se connaît qu'en les faisant.
            if (!NativeMesh.prepare(buildContext.nativeWorkspace, BlockFlags189.properties(), fluidData(commands),
                commands.words(), commands.count(), quads, buckets, this.render.getSectionIndex())) {
                return false;
            }
            for (int bucket = 0; bucket < buckets; bucket++) {
                if (quads[bucket] == 0) {
                    destinations[bucket] = 0L;
                } else {
                    ChunkMeshBufferBuilder buffer = commands.vertexBuffer(bucket);
                    firstVertex[bucket] = buffer.count();
                    destinations[bucket] = buffer.reserve(quads[bucket] * 4);
                }
            }
            if (!NativeMesh.emitQuads(buildContext.nativeWorkspace, BlockFlags189.properties(), commands.words(),
                commands.count(), destinations, buckets, this.render.getSectionIndex())) {
                return false;
            }
        } catch (Throwable failure) {
            LauncherLog.err("[RENDERER] exception à l'appel du noyau natif", failure);
            return false;
        }
        // Passe triée : le tri a besoin de la position des quads que le noyau vient d'écrire.
        for (int bucket = 0; bucket < buckets; bucket++) {
            if (quads[bucket] != 0) {
                commands.vertexBuffer(bucket).captureReserved(firstVertex[bucket], quads[bucket] * 4);
            }
        }
        return true;
    }

    /** Vrai si le tableau des fluides a la longueur que le noyau attend : sinon les fluides restent à vanilla. */
    private static final boolean NATIVE_FLUIDS = NativeMesh.available() && NativeMesh.fluidDataLength() == FluidData189.LENGTH;

    private static MemorySegment fluidData(QuadCommands commands) {
        return commands.hasFluids() ? FluidData189.data() : MemorySegment.NULL;
    }

    /**
     * Le contrôle : le Java a écrit les quads dans les tampons de la section ;
     * le noyau écrit les mêmes à côté, et les deux doivent être identiques à
     * l'octet près.
     */
    private void probeNativeQuads(PrimitiveChunkBuildContext buildContext, QuadCommands commands) {
        int buckets = commands.bucketCount();
        int[] quads = commands.bucketQuads();
        long quadBytes = 4L * NativeMesh.VERTEX_BYTES;
        long[] destinations = commands.destinations();
        MemorySegment scratch = null;
        int totalQuads = 0;

        String difference = null;
        try {
            if (!NativeMesh.prepare(buildContext.nativeWorkspace, BlockFlags189.properties(), fluidData(commands),
                commands.words(), commands.count(), quads, buckets, this.render.getSectionIndex())) {
                difference = "le noyau a rendu une erreur au comptage";
            } else {
                for (int bucket = 0; bucket < buckets; bucket++) {
                    totalQuads += quads[bucket];
                }
                scratch = buildContext.probeScratch(Math.max(1, totalQuads) * quadBytes);
                long at = 0;
                for (int bucket = 0; bucket < buckets; bucket++) {
                    destinations[bucket] = quads[bucket] == 0 ? 0L : scratch.address() + at;
                    at += quads[bucket] * quadBytes;
                }
                if (!NativeMesh.emitQuads(buildContext.nativeWorkspace, BlockFlags189.properties(), commands.words(),
                    commands.count(), destinations, buckets, this.render.getSectionIndex())) {
                    difference = "le noyau a rendu une erreur à l'écriture";
                }
            }
        } catch (Throwable failure) {
            difference = "exception à l'appel : " + failure;
        }

        long offset = 0;
        for (int bucket = 0; bucket < buckets && difference == null; bucket++) {
            ChunkMeshBufferBuilder javaBuffer = commands.vertexBuffer(bucket);
            long bytes = quads[bucket] * quadBytes;
            if (javaBuffer.count() != quads[bucket] * 4) {
                difference = "tampon " + bucket + " : " + javaBuffer.count() + " sommets en Java, " + quads[bucket] * 4 + " commandés";
            } else if (bytes > 0) {
                MemorySegment javaBytes = MemorySegment.ofBuffer(javaBuffer.slice());
                MemorySegment nativeBytes = scratch.asSlice(offset, bytes);
                long at = javaBytes.mismatch(nativeBytes);
                if (at >= 0) {
                    long word = at / 4;
                    difference = "tampon " + bucket + ", quad " + word / 32 + ", sommet " + word / 8 % 4
                        + ", mot " + word % 8 + " : Java "
                        + Integer.toHexString(javaBytes.get(ValueLayout.JAVA_INT_UNALIGNED, word * 4))
                        + ", natif " + Integer.toHexString(nativeBytes.get(ValueLayout.JAVA_INT_UNALIGNED, word * 4))
                        + " (section " + this.render.getOriginX() + " " + this.render.getOriginY() + " " + this.render.getOriginZ() + ")";
                }
            }
            offset += bytes;
        }
        MeshStats.probe(totalQuads, difference);
    }

    private static final AtomicBoolean NATIVE_FAILURE_WARNED = new AtomicBoolean();

    private void applyNativeShape(char[] blocks, BuiltRenderSectionData renderData) {
        if (blocks == null) {
            // Section vide : on voit tout à travers, rien à dessiner ni à occulter.
            renderData.visibilityData = VisibilityEncoding.EVERYTHING;
            renderData.occluderBoxes = null;
            return;
        }
        int[] out = new int[NativeMesh.SHAPE_OUT_LENGTH];
        int boxes = NativeMesh.sectionShape(blocks, BlockFlags189.table(), out);
        if (boxes == NativeMesh.SHAPE_FAILED) {
            // Image juste, culling en moins : tout se voit, la section entière est à dessiner.
            if (NATIVE_FAILURE_WARNED.compareAndSet(false, true)) {
                LauncherLog.err("[RENDERER] le noyau natif a échoué sur la forme d'une section — section traitée comme entièrement visible");
            }
            renderData.visibilityData = VisibilityEncoding.EVERYTHING;
            renderData.occluderBoxes = new int[] { PackedBox.FULL_BOX };
            return;
        }
        renderData.visibilityData = NativeMesh.visibility(out);
        renderData.occluderBoxes = boxes == NativeMesh.SHAPE_NO_DATA ? null : NativeMesh.occluderData(out, boxes);
    }

    /** Le chemin d'avant : vanilla dessine le bloc dans son tampon, que l'on recopie au format du moteur. */
    private void renderWithVanilla(BlockRenderManager vanillaRenderer, BlockState blockState, BlockPos blockPos,
                                   WorldSlice189 slice, BufferBuilder tesselator,
                                   PrimitiveChunkBuildContext buildContext, ChunkBuildBuffers buffers, Block block) {
        tesselator.begin(GL11C.GL_QUADS, VertexFormats.BLOCK);
        vanillaRenderer.renderBlock(blockState, blockPos, slice, tesselator);
        tesselator.end();
        buildContext.copyRawBuffer(tesselator.getByteBuffer().asIntBuffer(), tesselator.getVertexCount(), buffers,
            buffers.getRenderPassConfiguration().getMaterialForRenderType(block.getRenderLayerType()));
        tesselator.reset();
    }

    /** Les sortes de blocs déjà signalées : un avertissement par sorte, pas par bloc. */
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Un bloc que notre rendu ne sait pas dessiner part sur le rendu vanilla.
     * L'image reste juste, mais c'est un trou dans notre rendu : on le dit
     * fort, une fois par sorte de bloc, pour qu'il soit corrigé.
     */
    private static void warnFallback(Block block, String reason, Throwable failure) {
        if (!WARNED.add(block.getClass())) {
            return;
        }
        String bar = "##########################################################################";
        LauncherLog.err(bar);
        LauncherLog.err("##  [RENDERER] BLOC NON PRIS EN CHARGE — REPLI SUR LE RENDU VANILLA");
        LauncherLog.err("##  Classe du bloc (nom du jar) : " + block.getClass().getName());
        LauncherLog.err("##  Raison : " + reason);
        LauncherLog.err("##  Le bloc s'affiche, mais par l'ancien chemin. À signaler pour correction.");
        LauncherLog.err(bar);
        if (failure != null) {
            LauncherLog.err("[RENDERER] exception du rendu de blocs", failure);
        }
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
