package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.world.World;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderPassConfiguration;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexEncoder;
import com.yuyuframe.launcheragent.celeritas.util.QuadUtil;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.light.LightCache189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline.BlockRenderer189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.WorldSlice189;

import com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore.NativeMesh;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline.QuadCommands;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;

/**
 * Ce dont un thread de construction a besoin pour mailler une section : le
 * monde, et son propre tampon de sommets vanilla.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, branche « 1.8 et plus ».
 */
@YarnNamed
public class PrimitiveChunkBuildContext extends ChunkBuildContext {
    public final World world;
    /** Tampon dans lequel le rendu de blocs vanilla écrit, relu ensuite par {@link #copyRawBuffer}. */
    public final BufferBuilder tesselator;

    /** Le monde vu par ce fil : les copies figées de la section en cours et de ses voisines. */
    public final WorldSlice189 slice;
    /** La lumière et l'opacité des blocs, gardées le temps d'une section. */
    public final LightCache189 lightCache;
    /** Notre rendu de blocs, pour tout ce qui a un modèle. */
    public final BlockRenderer189 blockRenderer;

    public PrimitiveChunkBuildContext(World world, RenderPassConfiguration renderPassConfiguration) {
        super(renderPassConfiguration);
        this.world = world;
        this.tesselator = new BufferBuilder(8192);
        this.slice = new WorldSlice189(world);
        this.lightCache = new LightCache189(this.slice);
        this.blockRenderer = new BlockRenderer189(this.slice, this.lightCache);
        this.nativeWorkspace = NativeMesh.available() ? NativeMesh.allocateWorkspace() : null;
    }

    /** Les quads de la section en cours que le noyau natif écrira. */
    public final QuadCommands quadCommands = new QuadCommands();
    /** La mémoire de travail du noyau natif pour ce fil, ou {@code null} sans noyau. */
    public final MemorySegment nativeWorkspace;

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    /**
     * Recopie les quads écrits par vanilla au format {@code BLOCK} (7 entiers
     * par sommet : position, couleur, UV, lumière) dans les tampons du moteur.
     */
    public void copyRawBuffer(IntBuffer rawBuffer, int vertexCount, ChunkBuildBuffers buffers, Material material) {
        if (vertexCount == 0) {
            return;
        }

        // Des quads seulement.
        if ((vertexCount & 0x3) != 0) {
            throw new IllegalStateException();
        }

        ChunkVertexEncoder.Vertex[] celeritasVertices = this.vertices;
        int numQuads = vertexCount / 4;
        int ptr = 0;

        for (int quadIdx = 0; quadIdx < numQuads; quadIdx++) {
            for (int vIdx = 0; vIdx < 4; vIdx++) {
                ChunkVertexEncoder.Vertex vertex = celeritasVertices[vIdx];
                vertex.x = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.y = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.z = Float.intBitsToFloat(rawBuffer.get(ptr++));
                // À partir de la 1.8, la couleur précède les UV.
                vertex.color = rawBuffer.get(ptr++);
                vertex.u = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.v = Float.intBitsToFloat(rawBuffer.get(ptr++));
                vertex.light = rawBuffer.get(ptr++);
            }

            int trueNormal = QuadUtil.calculateNormal(celeritasVertices);

            for (int vIdx = 0; vIdx < 4; vIdx++) {
                celeritasVertices[vIdx].trueNormal = trueNormal;
            }

            ModelQuadFacing facing = QuadUtil.findNormalFace(trueNormal);

            buffers.get(material).getVertexBuffer(facing).push(celeritasVertices, material);
        }
    }
}
