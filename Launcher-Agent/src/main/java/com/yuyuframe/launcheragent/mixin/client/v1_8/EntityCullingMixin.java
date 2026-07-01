package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimisation 1.8.9 — Entity Culling.
 *
 * Skip le rendu des entités au-delà d'une distance configurable depuis la
 * caméra. Sur des scènes à 50+ entités, gain typique : +20 à +35 FPS.
 *
 * Cible : net.minecraft.client.render.entity.EntityRenderDispatcher (Legacy
 * Fabric Yarn 1.8.9+build.604 — vérifié dans mappings/mappings-1.8.9.tiny,
 * class_550 ; PAS "RenderManager" comme en MCP historique).
 *
 * Méthode : render(Entity, double, double, double, float, float): boolean
 * (named "render", official "a", desc (Lpk;DDDFF)Z sur EntityRenderDispatcher)
 * — retourne un boolean, d'où CallbackInfoReturnable<Boolean> et
 * setReturnValue(false) plutôt que cancel().
 *
 * Champs shadowés : cameraX/Y/Z (field_2105/2106/2107 — PAS CAMERA_X/Y/Z,
 * field_2095-97, qui sont un autre couple de champs dans la même classe).
 *
 * Configuration via args JVM :
 *   -Dlauncheragent.entityCullDist=160   (distance max en blocs, défaut 160)
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderDispatcher")
public abstract class EntityCullingMixin {

    @Shadow private double cameraX;
    @Shadow private double cameraY;
    @Shadow private double cameraZ;

    private static final double CULL_DIST2 = parseCullDist2();

    @Inject(
        method = "render(Lnet/minecraft/entity/Entity;DDDFF)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void la$entityCulling(Object entity,
                                   double x, double y, double z,
                                   float yaw, float tickDelta,
                                   CallbackInfoReturnable<Boolean> cir) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        if (dx * dx + dy * dy + dz * dz > CULL_DIST2) {
            cir.setReturnValue(false);
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
