package com.yuyuframe.launcheragent.apimixin.v1_8_9.freelook;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#FREELOOK_CAMERA_ROTATION_OFFSET} sur 1.8.9 — pendant de
 * {@code CameraFreelookMixin261}/{@code CameraFreelookMixin1211}.
 *
 * <h2>Pas d'objet caméra en 1.8.9</h2>
 *
 * La caméra est posée par {@code GameRenderer.transformCamera(F)V}
 * ({@code bfk.f}), qui lit directement {@code yaw}/{@code pitch}/
 * {@code prevYaw}/{@code prevPitch} de l'entité caméra — 18 lectures au javap,
 * y compris celles du raycast de recul en 3e personne. Plutôt que de rediriger
 * chacune, la rotation du joueur est décalée le temps de cette seule méthode :
 * en tête, rotation courante et précédente reçoivent le même décalage (donc
 * l'interpolation entre les deux est inchangée) ; au retour, les quatre
 * valeurs d'origine sont remises. Rien d'autre ne tourne entre les deux :
 * visée, déplacement et modèle du joueur voient toujours la vraie rotation. Et
 * le raycast de recul utilise la rotation décalée, donc la caméra ne traverse
 * pas les murs (bug historique de 26.1.2).
 *
 * <p>Entité : le joueur ({@link AccessPoint#CLIENT_PLAYER}), comme le
 * {@code turn} intercepté — en vue d'une autre entité (spectateur), le
 * décalage ne touche que le joueur et reste invisible.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class CameraFreelookMixin189 {

    /** Rotation d'origine du joueur pendant {@code transformCamera}, {@code null} hors décalage. */
    private static float[] la$saved;
    private static EntityRotationAccessor189 la$shifted;

    @Inject(method = "transformCamera(F)V", at = @At("HEAD"), require = 0)
    private void la$shiftRotation(float tickDelta, CallbackInfo ci) {
        la$saved = null;
        la$shifted = null;
        try {
            Object player = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER, null);
            if (!(player instanceof EntityRotationAccessor189)) return;
            EntityRotationAccessor189 entity = (EntityRotationAccessor189) player;
            float yaw = entity.la$yaw();
            float pitch = entity.la$pitch();
            Object result = VanillaHookRegistry.dispatchValue(HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET,
                new float[]{yaw, pitch});
            if (!(result instanceof float[])) return;
            float[] adjusted = (float[]) result;
            float dYaw = adjusted[0] - yaw;
            float dPitch = adjusted[1] - pitch;
            la$saved = new float[]{yaw, pitch, entity.la$prevYaw(), entity.la$prevPitch()};
            la$shifted = entity;
            entity.la$setYaw(yaw + dYaw);
            entity.la$setPitch(pitch + dPitch);
            entity.la$setPrevYaw(la$saved[2] + dYaw);
            entity.la$setPrevPitch(la$saved[3] + dPitch);
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin189] décalage : " + t);
        }
    }

    @Inject(method = "transformCamera(F)V", at = @At("RETURN"), require = 0)
    private void la$restoreRotation(float tickDelta, CallbackInfo ci) {
        float[] saved = la$saved;
        EntityRotationAccessor189 entity = la$shifted;
        la$saved = null;
        la$shifted = null;
        if (saved == null || entity == null) return;
        try {
            entity.la$setYaw(saved[0]);
            entity.la$setPitch(saved[1]);
            entity.la$setPrevYaw(saved[2]);
            entity.la$setPrevPitch(saved[3]);
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin189] restauration : " + t);
        }
    }
}
