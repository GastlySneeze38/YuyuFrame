package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.level.LevelGeneratorType;

/**
 * Le monde tel que le voit une tâche de construction : la section à mailler
 * et ses 26 voisines, lues dans leurs copies figées ({@link SectionSnapshot}).
 *
 * <p>C'est une {@code BlockView} du jeu : le code des blocs (forme réelle,
 * faces à cacher, teinte, lumière) et le rendu vanilla de repli la consultent
 * sans savoir qu'ils ne lisent pas le monde vivant. Un exemplaire par fil de
 * construction, réutilisé d'une tâche à l'autre.
 *
 * <p>Repris, pour la logique de lumière, de l'adaptateur 1.12.2 de Celeritas
 * ({@code WorldSlice}) et du {@code World} de la 1.8.9 ; la lecture se fait ici
 * dans les tableaux de la copie, pas dans des objets par bloc.
 *
 * <p>Hors des 27 sections, tout est de l'air en plein jour : le maillage ne
 * regarde jamais plus loin que deux blocs autour de la section.
 */
@YarnNamed
public final class WorldSlice189 implements BlockView {

    private static final int DEFAULT_SKY_LIGHT = 15;

    private final World world;
    private final boolean hasSkyLight;
    private final BlockState air;

    private SectionSnapshot[] sections;
    /** Coin de la section d'indice 0 (celle en bas, à l'ouest, au nord), en blocs. */
    private int baseX, baseY, baseZ;

    public WorldSlice189(World world) {
        this.world = world;
        this.hasSkyLight = !world.dimension.hasNoSkylight();
        this.air = Blocks.AIR.getDefaultState();
    }

    public void init(ChunkRenderContext context) {
        this.sections = context.sections();
        this.baseX = (context.origin().x() - 1) << 4;
        this.baseY = (context.origin().y() - 1) << 4;
        this.baseZ = (context.origin().z() - 1) << 4;
    }

    /** Lâche les copies : la tâche est finie, elles n'ont pas à vivre jusqu'à la suivante. */
    public void release() {
        this.sections = null;
    }

    /** La section qui contient ce bloc, ou {@code null} hors des 27 sections copiées. */
    private SectionSnapshot sectionAt(int x, int y, int z) {
        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;
        if ((relX | relY | relZ) < 0 || relX >= 48 || relY >= 48 || relZ >= 48) {
            return null;
        }
        return this.sections[(relX >> 4) + 3 * ((relZ >> 4) + 3 * (relY >> 4))];
    }

    // ── Blocs ────────────────────────────────────────────────────────────────

    /**
     * L'état du bloc à ces coordonnées. Nom distinct de {@code getBlockState}
     * exprès : une méthode de même nom qu'une méthode du jeu risquerait d'être
     * renommée avec elle au chargement.
     */
    public BlockState stateAt(int x, int y, int z) {
        SectionSnapshot section = this.sectionAt(x, y, z);
        if (section == null || section.blocks == null) {
            return this.air;
        }
        Object state = Block.BLOCK_STATES.fromId(section.blocks[SectionSnapshot.index(x & 15, y & 15, z & 15)]);
        return state == null ? this.air : (BlockState) state;
    }

    // Les coordonnées d'une position se lisent sur un receveur typé Vec3i :
    // c'est là que Yarn range getX/getY/getZ. Sur BlockPos, le traducteur de
    // noms trouve plusieurs candidates et ne traduit pas — plantage du
    // 2026-10-06 (NoSuchMethodError: cj.getX()).

    private static int xOf(Vec3i pos) {
        return pos.getX();
    }

    private static int yOf(Vec3i pos) {
        return pos.getY();
    }

    private static int zOf(Vec3i pos) {
        return pos.getZ();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.stateAt(xOf(pos), yOf(pos), zOf(pos));
    }

    @Override
    public boolean isAir(BlockPos pos) {
        return this.stateAt(xOf(pos), yOf(pos), zOf(pos)).getBlock() == Blocks.AIR;
    }

    public BlockEntity blockEntityAt(int x, int y, int z) {
        SectionSnapshot section = this.sectionAt(x, y, z);
        return section == null ? null : (BlockEntity) section.blockEntity(x & 15, y & 15, z & 15);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.blockEntityAt(xOf(pos), yOf(pos), zOf(pos));
    }

    @Override
    public int getStrongRedstonePower(BlockPos pos, Direction direction) {
        BlockState state = this.stateAt(xOf(pos), yOf(pos), zOf(pos));
        return state.getBlock().getStrongRedstonePower(this, pos, state, direction);
    }

    // ── Lumière ──────────────────────────────────────────────────────────────

    /**
     * La lumière d'un bloc, comme {@code World.getLight(pos, min)} de la
     * 1.8.9 : ciel dans les bits 20 et plus, blocs dans les bits 4 et plus.
     */
    @Override
    public int getLight(BlockPos pos, int minBlockLight) {
        int x = xOf(pos);
        int y = yOf(pos);
        int z = zOf(pos);
        int sky = this.lightWithNeighbours(true, x, y, z);
        int block = this.lightWithNeighbours(false, x, y, z);
        if (block < minBlockLight) {
            block = minBlockLight;
        }
        return sky << 20 | block << 4;
    }

    /**
     * Un bloc qui « prend la lumière de ses voisins » (dalle, escalier, terre
     * labourée) n'a pas de lumière propre : vanilla lui donne la plus forte de
     * celles du dessus et des quatre côtés — pas du dessous.
     */
    private int lightWithNeighbours(boolean sky, int x, int y, int z) {
        if (sky && !this.hasSkyLight) {
            return 0;
        }
        if (y < 0) {
            y = 0;
        }
        if (y > 255) {
            return sky ? DEFAULT_SKY_LIGHT : 0;
        }
        if (!this.stateAt(x, y, z).getBlock().usesNeighbourLight()) {
            return this.lightAt(sky, x, y, z);
        }
        int light = this.lightAt(sky, x, y + 1, z);
        light = Math.max(light, this.lightAt(sky, x + 1, y, z));
        light = Math.max(light, this.lightAt(sky, x - 1, y, z));
        light = Math.max(light, this.lightAt(sky, x, y, z + 1));
        light = Math.max(light, this.lightAt(sky, x, y, z - 1));
        return light;
    }

    private int lightAt(boolean sky, int x, int y, int z) {
        if (y > 255) {
            return sky ? DEFAULT_SKY_LIGHT : 0;
        }
        SectionSnapshot section = this.sectionAt(x, y, z);
        if (section == null) {
            return sky ? DEFAULT_SKY_LIGHT : 0;
        }
        if (section.blocks == null) {
            // Section vide : pas de lumière de blocs, et le ciel n'éclaire qu'au-dessus du plus haut bloc.
            if (!sky) {
                return 0;
            }
            return y >= section.heights[(z & 15) << 4 | (x & 15)] ? DEFAULT_SKY_LIGHT : 0;
        }
        byte[] data = sky ? section.skyLight : section.blockLight;
        if (data == null) {
            return 0;
        }
        return SectionSnapshot.nibble(data, SectionSnapshot.index(x & 15, y & 15, z & 15));
    }

    // ── Biome et monde ───────────────────────────────────────────────────────

    @Override
    public Biome getBiome(BlockPos pos) {
        // Le biome ne dépend pas de la hauteur : la section du milieu en hauteur fait l'affaire.
        int x = xOf(pos);
        int z = zOf(pos);
        SectionSnapshot section = this.sectionAt(x, this.baseY + 16, z);
        if (section != null) {
            Biome biome = Biome.byId(section.biomes[(z & 15) << 4 | (x & 15)] & 255);
            if (biome != null) {
                return biome;
            }
        }
        // Hors zone, ou biome pas encore reçu : on demande au monde vivant, comme vanilla.
        BlockView live = this.world;
        return live.getBiome(pos);
    }

    @Override
    public LevelGeneratorType getGeneratorType() {
        BlockView live = this.world;
        return live.getGeneratorType();
    }

    @Override
    public boolean isEmpty() {
        return false;
    }
}
