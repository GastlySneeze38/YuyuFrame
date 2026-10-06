package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.render.RenderLayer;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderPassConfiguration;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.sorting.QuadPrimitiveType;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.parameters.AlphaCutoffParameter;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkMeshFormats;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.opengl.GL11;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Les passes de rendu du terrain et la correspondance avec les quatre couches
 * de blocs de vanilla ({@code RenderLayer}).
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas, branche « 1.8 et
 * plus » seulement.
 */
@YarnNamed
public class PrimitiveRenderPassConfigurationBuilder {
    public static final TerrainRenderPass SOLID_PASS, CUTOUT_MIPPED_PASS, TRANSLUCENT_PASS;
    public static final Material SOLID_MATERIAL, CUTOUT_MATERIAL, CUTOUT_MIPPED_MATERIAL, TRANSLUCENT_MATERIAL;

    /** Sans type du jeu : n'a pas besoin du marqueur {@link YarnNamed}. */
    private record PrimitivePipelineState(boolean disableBlend) implements TerrainRenderPass.PipelineState {
        @Override
        public void setup() {
            if (disableBlend) {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
            }
        }

        @Override
        public void clear() {
            if (disableBlend) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
            }
        }
    }

    private static TerrainRenderPass.TerrainRenderPassBuilder builderForRenderType(boolean disableBlend) {
        var builder = TerrainRenderPass.builder();
        builder.pipelineState(new PrimitivePipelineState(disableBlend));
        builder.vertexType(ChunkMeshFormats.VANILLA_LIKE).primitiveType(QuadPrimitiveType.TRIANGULATED);
        return builder;
    }

    static {
        SOLID_PASS = builderForRenderType(true)
                .name("solid")
                .fragmentDiscard(false)
                .useReverseOrder(false)
                .build();
        CUTOUT_MIPPED_PASS = builderForRenderType(false)
                .name("cutout_mipped")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .build();
        TRANSLUCENT_PASS = builderForRenderType(false)
                .name("translucent")
                .fragmentDiscard(false)
                .useReverseOrder(true)
                .useTranslucencySorting(true)
                .build();

        TRANSLUCENT_MATERIAL = new Material(TRANSLUCENT_PASS, AlphaCutoffParameter.ZERO, true);
        SOLID_MATERIAL = new Material(SOLID_PASS, AlphaCutoffParameter.ZERO, true);
        CUTOUT_MIPPED_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, true);
        CUTOUT_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, false);
    }

    public static RenderPassConfiguration<?> build(ChunkVertexType vertexType) {
        // Vanilla appelle le rendu du terrain une fois par couche. On répond à
        // SOLID par nos deux passes opaques et à TRANSLUCENT par la passe
        // triée ; les appels CUTOUT et CUTOUT_MIPPED n'ont alors rien à dessiner.
        Map<RenderLayer, Collection<TerrainRenderPass>> vanillaRenderStages = new Reference2ReferenceOpenHashMap<>();
        vanillaRenderStages.put(RenderLayer.SOLID, List.of(SOLID_PASS, CUTOUT_MIPPED_PASS));
        vanillaRenderStages.put(RenderLayer.TRANSLUCENT, List.of(TRANSLUCENT_PASS));

        Map<RenderLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>();
        renderTypeToMaterialMap.put(RenderLayer.SOLID, SOLID_MATERIAL);
        renderTypeToMaterialMap.put(RenderLayer.CUTOUT, CUTOUT_MATERIAL);
        renderTypeToMaterialMap.put(RenderLayer.CUTOUT_MIPPED, CUTOUT_MIPPED_MATERIAL);
        renderTypeToMaterialMap.put(RenderLayer.TRANSLUCENT, TRANSLUCENT_MATERIAL);

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStages,
                CUTOUT_MIPPED_MATERIAL,
                CUTOUT_MIPPED_MATERIAL,
                TRANSLUCENT_MATERIAL);
    }
}
