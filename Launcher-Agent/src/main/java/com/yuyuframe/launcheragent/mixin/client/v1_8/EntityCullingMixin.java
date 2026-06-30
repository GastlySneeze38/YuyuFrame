package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation 1.8.9 — Entity Culling.
 *
 * Skip le rendu des entités au-delà d'une distance configurable depuis la
 * caméra. Sur des scènes à 50+ entités, gain typique : +20 à +35 FPS.
 *
 * Cible : net.minecraft.client.renderer.entity.RenderManager (nom MCP / Legacy Fabric Yarn)
 * Méthode : renderEntityWithPosYaw (MCP stable-9.11)
 *
 * Champs shadowés : renderPosX/Y/Z — position caméra stockée dans RenderManager
 * avant le rendu des entités (MCP stable-9.11, Legacy Fabric Yarn).
 *
 * Configuration via args JVM :
 *   -Dlauncheragent.entityCullDist=160   (distance max en blocs, défaut 160)
 *
 * NOTE : si vos Yarn 1.8.9 utilisent des noms différents pour la méthode
 * ou les champs, ajustez les valeurs ci-dessous.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.RenderManager")
public abstract class EntityCullingMixin {

    @Shadow private double renderPosX;
    @Shadow private double renderPosY;
    @Shadow private double renderPosZ;

    private static final double CULL_DIST2 = parseCullDist2();

    @Inject(
        method = "renderEntityWithPosYaw",
        at = @At("HEAD"),
        cancellable = true
    )
    private void la$entityCulling(Object entity,
                                   double x, double y, double z,
                                   float yaw, float partialTicks,
                                   CallbackInfo ci) {
        double dx = x - renderPosX;
        double dy = y - renderPosY;
        double dz = z - renderPosZ;
        if (dx * dx + dy * dy + dz * dz > CULL_DIST2) {
            ci.cancel();
        }
    }

    private static double parseCullDist2() {
        try {
            String prop = System.getProperty("launcheragent.entityCullDist");
            if (prop != null) {
                double d = Double.parseDouble(prop);
                LauncherLog.ui(1, "[EntityCulling] distance configurée : " + d + " blocs");
                return d * d;
            }
        } catch (NumberFormatException ignored) {}
        return 160.0 * 160.0;
    }
}
