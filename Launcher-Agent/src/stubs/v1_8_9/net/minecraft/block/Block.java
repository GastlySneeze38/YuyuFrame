package net.minecraft.block;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code afh}) — {@code Block} chez Feather.
 *
 * <p>Membres : ceux que cite l'adaptateur Celeritas ({@code celeritas/v1_8_9/FEATHER-YARN.md}).
 */
public class Block {

    protected Block() {
    }

    /** {@code afh.S} — {@code init} chez Feather. */
    public static void setup() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.t()} */
    public net.minecraft.block.material.Material getMaterial() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.o()} */
    public boolean isFullBlock() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.c} — {@code isOpaqueCube} chez Feather. */
    public boolean hasTransparency() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.m} — {@code getRenderLayer} chez Feather. */
    public net.minecraft.client.render.RenderLayer getRenderLayerType() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    // ── Lus par le rendu de blocs du renderer (celeritas/v1_8_9/terrain/compile) ──

    /** {@code afh.d} — tous les états de bloc, par identifiant. */
    public static net.minecraft.util.collection.IdList BLOCK_STATES;

    /** {@code afh$a} — décalage aléatoire du modèle selon la position (herbes, fleurs). */
    public enum OffsetType {
        NONE, XZ, XYZ
    }

    /** {@code afh.Q()} */
    public final BlockState getDefaultState() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.b()} — −1 : rien, 1 : fluide, 2 : block entity seulement, 3 : modèle. */
    public int getBlockType() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.r()} — lumière émise, 0 à 15. */
    public int getLightLevel() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.p()} — lumière absorbée, 0 à 255. */
    public int getOpacity() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.q()} */
    public boolean isTranslucent() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.u()} — cube plein dont la matière bloque le passage ; c'est lui qui assombrit l'occlusion ambiante. */
    public boolean isNormalBlock() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.h()} — 0,2 pour un cube plein, 1 sinon. */
    public float getAmbientOcclusionLightLevel() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.s()} — dalles, escaliers, terre labourée : prennent la lumière de leurs voisins. */
    public boolean usesNeighbourLight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.R()} */
    public OffsetType getOffsetType() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.a(adq, cj)} — recale la boîte du bloc sur son état dans le monde ; à appeler avant {@link #isSideInvisible}. */
    public void setBoundingBox(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * {@code afh.a(adq, cj, cq)} — nom Yarn trompeur : rend VRAI si la face doit
     * être DESSINÉE. {@code neighborPos} est la position du voisin, pas du bloc.
     */
    public boolean isSideInvisible(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos neighborPos,
                                   net.minecraft.util.math.Direction side) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.a(adq, cj, I)} — teinte d'une couche du modèle à cette position (herbe, feuillage, eau). */
    public int getBlockColor(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos, int layer) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.c(adq, cj)} — lumière reçue à cette position, ciel dans les bits hauts. */
    public int getBrightness(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.b(adq, cj, alz, cq)} */
    public int getStrongRedstonePower(net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos,
                                      BlockState state, net.minecraft.util.math.Direction direction) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code afh.z} — {@code hasBlockEntity} chez Feather. */
    public boolean hasBlockEntity() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
