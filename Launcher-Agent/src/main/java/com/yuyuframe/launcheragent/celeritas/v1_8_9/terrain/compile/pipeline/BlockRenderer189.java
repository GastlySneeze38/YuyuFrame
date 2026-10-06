package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.chunk.ChunkColorWriter;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.buffers.ChunkModelBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexEncoder;
import com.yuyuframe.launcheragent.celeritas.model.light.DiffuseProvider;
import com.yuyuframe.launcheragent.celeritas.model.light.LightMode;
import com.yuyuframe.launcheragent.celeritas.model.light.LightPipeline;
import com.yuyuframe.launcheragent.celeritas.model.light.LightPipelineProvider;
import com.yuyuframe.launcheragent.celeritas.model.light.data.QuadLightData;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadOrientation;
import com.yuyuframe.launcheragent.celeritas.util.ModelQuadUtil;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.light.LightCache189;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.world.WorldSlice189;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.util.math.BlockPos;

/**
 * Notre rendu de blocs : il remplace celui de vanilla pour tous les blocs à
 * modèle. Porté de l'adaptateur 1.12.2 de Celeritas
 * ({@code VintageBlockRenderer}), lui-même dérivé de Sodium.
 *
 * <p>Pour un bloc, il reste trois choses à demander au jeu, parce qu'elles
 * dépendent du code de chaque bloc :
 * <ul>
 *   <li>son modèle à cet endroit (barrière reliée, escalier d'angle,
 *       variante aléatoire) ;</li>
 *   <li>pour chaque face, si elle se dessine ou si le voisin la cache ;</li>
 *   <li>sa teinte (herbe et feuillage selon le biome).</li>
 * </ul>
 * Tout le reste est à nous : la lumière et l'occlusion ambiante de chaque
 * sommet par le calcul du moteur, puis l'écriture directe au format final —
 * là où vanilla écrivait dans un tampon que l'on recopiait ensuite.
 *
 * <p><b>L'image n'est pas celle de vanilla au pixel près</b> : l'éclairage
 * adouci du moteur (celui de Sodium) est plus régulier dans les coins.
 * Décision du 2026-10-06.
 *
 * <p>Un exemplaire par fil de construction.
 */
@YarnNamed
public final class BlockRenderer189 {

    private final WorldSlice189 slice;
    private final LightPipelineProvider lighters;
    private final BlockRenderManager blockRenderManager;

    private final QuadLightData light = new QuadLightData();
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
    private final BlockPos.Mutable neighbour = new BlockPos.Mutable();

    /**
     * Le sens dans lequel couper chaque face en deux triangles, choisi d'après
     * la lumière du premier quad de la face et gardé pour les suivants : deux
     * quads d'une même face coupés différemment laisseraient voir un pli.
     */
    private final ModelQuadOrientation[] orientations = new ModelQuadOrientation[6];

    public BlockRenderer189(WorldSlice189 slice, LightCache189 lightCache) {
        this.slice = slice;
        // L'ombrage de face est déjà dans la couleur des quads de la 1.8.9 : le moteur n'a pas à l'ajouter.
        this.lighters = new LightPipelineProvider(lightCache, DiffuseProvider.NONE, true);
        this.blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();
    }

    /** Avant chaque section : les caches de lumière du moteur repartent de zéro. */
    public void reset() {
        this.lighters.reset();
    }

    public void render(BlockState state, BlockPos pos, ChunkBuildBuffers buffers) {
        Block block = state.getBlock();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        BlockQuadCache189.Model model = BlockQuadCache189.of(this.blockRenderManager.getModel(state, this.slice, pos));

        Material material = buffers.getRenderPassConfiguration().getMaterialForRenderType(block.getRenderLayerType());
        ChunkModelBuilder builder = buffers.get(material);

        boolean smooth = MinecraftClient.isAmbientOcclusionEnabled() && block.getLightLevel() == 0 && model.ambientOcclusion;
        LightPipeline lighter = this.lighters.getLighter(smooth ? LightMode.SMOOTH : LightMode.FLAT);

        // Certains blocs (dalles, escaliers) ne connaissent leur boîte qu'une
        // fois recalée sur le monde, et s'en servent pour dire si une face est
        // cachée. Vanilla fait cet appel au même endroit.
        block.setBoundingBox(this.slice, pos);

        // Herbes et fleurs : le modèle est décalé d'une fraction de bloc, selon la position. Formule de vanilla.
        float offsetX = 0.0F;
        float offsetY = 0.0F;
        float offsetZ = 0.0F;
        Block.OffsetType offsetType = block.getOffsetType();
        if (offsetType != Block.OffsetType.NONE) {
            long seed = (long) (x * 3129871) ^ (long) z * 116129781L;
            seed = seed * seed * 42317861L + seed * 11L;
            offsetX = (float) (((double) ((float) (seed >> 16 & 15L) / 15.0F) - 0.5D) * 0.5D);
            offsetZ = (float) (((double) ((float) (seed >> 24 & 15L) / 15.0F) - 0.5D) * 0.5D);
            if (offsetType == Block.OffsetType.XYZ) {
                offsetY = (float) (((double) ((float) (seed >> 20 & 15L) / 15.0F) - 1.0D) * 0.2D);
            }
        }

        int localX = x & 15;
        int localY = y & 15;
        int localZ = z & 15;

        this.tintLayer = Integer.MIN_VALUE;

        for (int face = 0; face < 6; face++) {
            CachedQuad[] quads = model.byFace[face];
            if (quads.length == 0) {
                continue;
            }
            ModelQuadFacing facing = BlockQuadCache189.FACINGS[face];
            this.neighbour.setPosition(x + facing.getStepX(), y + facing.getStepY(), z + facing.getStepZ());
            // Nom Yarn trompeur : vrai = la face doit être dessinée.
            if (!block.isSideInvisible(this.slice, this.neighbour, BlockQuadCache189.DIRECTIONS[face])) {
                continue;
            }
            this.orientations[face] = null;
            for (int i = 0; i < quads.length; i++) {
                this.renderQuad(quads[i], block, pos, x, y, z, localX + offsetX, localY + offsetY, localZ + offsetZ,
                    facing, face, lighter, builder, material);
            }
        }

        CachedQuad[] inner = model.inner;
        for (int i = 0; i < inner.length; i++) {
            this.renderQuad(inner[i], block, pos, x, y, z, localX + offsetX, localY + offsetY, localZ + offsetZ,
                ModelQuadFacing.UNASSIGNED, -1, lighter, builder, material);
        }
    }

    /** La dernière teinte demandée au bloc, et pour quelle couche : la plupart des quads d'un bloc partagent la leur. */
    private int tintLayer;
    private int tint;

    private void renderQuad(CachedQuad quad, Block block, BlockPos pos, int x, int y, int z,
                            float originX, float originY, float originZ,
                            ModelQuadFacing cullFace, int face, LightPipeline lighter,
                            ChunkModelBuilder builder, Material material) {
        QuadLightData light = this.light;
        lighter.calculate(quad, x, y, z, light, cullFace, quad.getLightFace(), quad.hasShade(), false);

        // −1 : pas de teinte. Sinon la couleur du bloc ici, en rouge-vert-bleu, rangée comme le moteur l'attend.
        int tint = -1;
        if (quad.hasColor()) {
            if (quad.getColorIndex() != this.tintLayer) {
                int rgb = block.getBlockColor(this.slice, pos, quad.getColorIndex());
                this.tint = 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
                this.tintLayer = quad.getColorIndex();
            }
            tint = this.tint;
        }

        ModelQuadOrientation orientation;
        if (face < 0) {
            orientation = ModelQuadOrientation.NORMAL;
        } else {
            orientation = this.orientations[face];
            if (orientation == null) {
                orientation = ModelQuadOrientation.orientByBrightness(light.br, light.lm);
                this.orientations[face] = orientation;
            }
        }

        ModelQuadFacing normalFace = quad.getNormalFace();
        int vanillaNormal = normalFace.getPackedNormal();
        int trueNormal = quad.getComputedFaceNormal();

        ChunkVertexEncoder.Vertex[] vertices = this.vertices;
        for (int dst = 0; dst < 4; dst++) {
            int src = orientation.getVertexIndex(dst);
            ChunkVertexEncoder.Vertex out = vertices[dst];
            out.x = originX + quad.getX(src);
            out.y = originY + quad.getY(src);
            out.z = originZ + quad.getZ(src);
            out.color = ChunkColorWriter.EMBEDDIUM.writeColor(ModelQuadUtil.mixARGBColors(tint, quad.getColor(src)), light.br[src]);
            out.u = quad.getTexU(src);
            out.v = quad.getTexV(src);
            out.light = light.lm[src];
            out.vanillaNormal = vanillaNormal;
            out.trueNormal = trueNormal;
        }

        builder.getVertexBuffer(normalFace).push(vertices, material);
    }
}
