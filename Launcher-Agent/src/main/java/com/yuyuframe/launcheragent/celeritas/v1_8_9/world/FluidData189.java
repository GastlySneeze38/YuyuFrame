package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.MathHelper;

import java.lang.foreign.MemorySegment;

/**
 * Ce que le noyau natif a besoin de savoir pour dessiner l'eau et la lave,
 * sans pouvoir le demander au jeu — notre code. Un tableau de flottants, dont
 * le format est celui de {@code FluidData} dans
 * {@code content-core/src/mesh/fluid.rs} :
 * <ul>
 *   <li>les bornes dans l'atlas des quatre textures (lave immobile, lave qui
 *       coule, eau immobile, eau qui coule) — elles changent quand les
 *       ressources sont rechargées, d'où {@link #invalidate} ;</li>
 *   <li>la table du courant : le noyau calcule le courant d'un bloc sous
 *       forme de deux entiers, et lit ici le sinus et le cosinus de son angle.
 *       Ils sont calculés avec les fonctions du jeu elles-mêmes (son arc
 *       tangente approchée, son sinus par table), pour que l'image soit celle
 *       de vanilla au bit près sans refaire ces fonctions en Rust.</li>
 * </ul>
 */
@YarnNamed
public final class FluidData189 {

    private static final String[] SPRITES = {
        "minecraft:blocks/lava_still", "minecraft:blocks/lava_flow",
        "minecraft:blocks/water_still", "minecraft:blocks/water_flow",
    };

    /** Le courant va de −32 à 32 sur chaque axe. */
    private static final int FLOW_RANGE = 32;
    private static final int FLOW_SIDE = 2 * FLOW_RANGE + 1;
    private static final int SPRITE_VALUES = 16;
    public static final int LENGTH = SPRITE_VALUES + FLOW_SIDE * FLOW_SIDE * 2 * 2;

    private static volatile MemorySegment data;

    private FluidData189() {
    }

    /** Ressources rechargées : les textures ont changé de place dans l'atlas. */
    public static void invalidate() {
        data = null;
    }

    public static MemorySegment data() {
        MemorySegment current = data;
        if (current == null) {
            current = MemorySegment.ofArray(build());
            data = current;
        }
        return current;
    }

    private static float[] build() {
        float[] values = new float[LENGTH];

        SpriteAtlasTexture atlas = MinecraftClient.getInstance().getSpriteAtlasTexture();
        for (int i = 0; i < SPRITES.length; i++) {
            Sprite sprite = atlas.getSprite(SPRITES[i]);
            values[i * 4] = sprite.getMinU();
            values[i * 4 + 1] = sprite.getMaxU();
            values[i * 4 + 2] = sprite.getMinV();
            values[i * 4 + 3] = sprite.getMaxV();
        }

        for (int falling = 0; falling < 2; falling++) {
            for (int z = -FLOW_RANGE; z <= FLOW_RANGE; z++) {
                for (int x = -FLOW_RANGE; x <= FLOW_RANGE; x++) {
                    int at = SPRITE_VALUES + 2 * ((x + FLOW_RANGE) + FLOW_SIDE * ((z + FLOW_RANGE) + FLOW_SIDE * falling));
                    float angle = (float) flowAngle(x, z, falling != 0);
                    // Vanilla : au-dessus de −999, l'eau coule ; l'angle « pas de courant » vaut −1000.
                    if (angle > -999.0F) {
                        values[at] = MathHelper.sin(angle) * 0.25F;
                        values[at + 1] = MathHelper.cos(angle) * 0.25F;
                    } else {
                        values[at] = Float.NaN;
                        values[at + 1] = Float.NaN;
                    }
                }
            }
        }
        return values;
    }

    /**
     * La fin de {@code AbstractFluidBlock.getFlowVector} puis
     * {@code getFlowAngle} (relevées dans le bytecode d'{@code ahv}), à partir
     * des deux composantes entières du courant : normalisation, chute, angle.
     */
    private static double flowAngle(int flowX, int flowZ, boolean falling) {
        double x = flowX;
        double y = 0.0;
        double z = flowZ;
        if (falling) {
            double length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0E-4) {
                x = 0.0;
                y = 0.0;
                z = 0.0;
            } else {
                x = x / length;
                y = y / length;
                z = z / length;
            }
            y += -6.0;
        }
        double length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0E-4) {
            x = 0.0;
            z = 0.0;
        } else {
            x = x / length;
            z = z / length;
        }
        if (x == 0.0 && z == 0.0) {
            return -1000.0;
        }
        return MathHelper.atan2(z, x) - Math.PI / 2;
    }
}
