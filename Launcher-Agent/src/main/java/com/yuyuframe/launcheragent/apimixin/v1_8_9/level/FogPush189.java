package com.yuyuframe.launcheragent.apimixin.v1_8_9.level;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.FogOverride;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.block.material.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * « Sans brouillard » et « Vision claire » sur 1.8.9.
 *
 * <p>Cette version n'a ni {@code FogData} ni un environnement par source :
 * tout le brouillard se règle dans une seule méthode,
 * {@code GameRenderer.renderFog(int, float)} ({@code bfk.a(IF)V}), qui pose
 * l'état OpenGL — linéaire pour la distance de rendu et la cécité,
 * exponentiel sous l'eau et dans la lave. On passe donc APRÈS elle : si un
 * module demande de repousser le brouillard de la matière où se trouve la
 * caméra, l'état est réécrit en linéaire, très loin.
 *
 * <p>Les modules ne savent rien de tout ça : ils répondent aux mêmes points
 * d'accroche que sur les autres versions ({@code FOG_SETUP_WATER}…). La
 * matière est déduite ici, dans l'ordre où vanilla la teste — cécité, eau,
 * lave, sinon le brouillard de distance.
 *
 * <p>Passer par {@link GlStateManager} et non par OpenGL directement : il
 * garde l'état en cache, et vanilla ne reposerait pas une valeur qu'il croit
 * déjà en place. C'est aussi ce qui fait marcher le tout avec notre renderer
 * de terrain, qui relit le brouillard dans l'état OpenGL une fois par image
 * ({@code GLStateManagerFogService}) : repoussé ici, il l'est pour lui aussi.
 */
@YarnNamed
public final class FogPush189 {

    private FogPush189() {
    }

    /** Identifiant numérique de l'effet Cécité sur cette version. */
    private static final int BLINDNESS = 15;
    private static final int GL_LINEAR = 9729;

    /** À la sortie de {@code GameRenderer.renderFog}. */
    public static void afterVanilla() {
        Entity camera = MinecraftClient.getInstance().getCameraEntity();
        if (camera == null) {
            return;
        }
        HookPoint point;
        if (camera instanceof LivingEntity && ((LivingEntity) camera).hasStatusEffect(BLINDNESS)) {
            point = HookPoint.FOG_SETUP_BLINDNESS;
        } else if (camera.isSubmergedIn(Material.WATER)) {
            point = HookPoint.FOG_SETUP_WATER;
        } else if (camera.isSubmergedIn(Material.LAVA)) {
            point = HookPoint.FOG_SETUP_LAVA;
        } else {
            point = HookPoint.FOG_SETUP_ATMOSPHERIC;
        }
        if (!VanillaHookRegistry.dispatch(point, null)) {
            return;
        }
        GlStateManager.fogMode(GL_LINEAR);
        GlStateManager.fogStart(FogOverride.FAR);
        GlStateManager.fogEnd(FogOverride.FAR * 2.0F);
    }
}
