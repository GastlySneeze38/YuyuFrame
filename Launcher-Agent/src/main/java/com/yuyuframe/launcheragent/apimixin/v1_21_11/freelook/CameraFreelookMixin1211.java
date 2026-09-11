package com.yuyuframe.launcheragent.apimixin.v1_21_11.freelook;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#FREELOOK_CAMERA_ROTATION_OFFSET} sur 1.21.11 — pendant de
 * {@code CameraFreelookMixin261}.
 *
 * <h2>Même point, forme différente</h2>
 *
 * 1.21.11 n'a pas {@code Camera.alignWithEntity} : tout se passe dans
 * {@code Camera.update(World, Entity, boolean, boolean, float)} (Mojang
 * {@code setup}). Désassemblage du vrai jar : {@code setRotation(FF)V} y est
 * appelée QUATRE fois, dans le même ordre qu'en 26.1.2 —
 * <pre>
 *   #0 sommeil (setRotation puis setPos(Vec3))
 *   #1 branche normale : setRotation(entity.yaw, entity.pitch) puis setPos(DDD)
 *   #2 vue AVANT seulement : setRotation(yaw + 180, -pitch)
 *   puis getMaxZoom (raycast de collision) et move()
 *   #3 longue-vue
 * </pre>
 * Le point à corriger est donc le même : l'appel #1, AVANT le retournement de
 * la vue avant et AVANT le raycast — sans quoi la caméra traverse les murs
 * (bug historique, voir {@code CameraFreelookMixin261}).
 *
 * <p>26.1.2 enveloppe l'appel ({@code @WrapOperation}). Ici on s'injecte juste
 * APRÈS lui et on ré-appelle {@code setRotation} avec la rotation corrigée : la
 * rotation finale et tout ce qui suit (#2, raycast, recul) sont identiques. Ce
 * choix évite le seul point incertain de cette version — un receveur
 * {@code Camera} à typer dans un handler MixinExtras alors que la classe est
 * obfusquée : ici tout passe par le cœur de Mixin et par
 * {@link CameraAccessor1211}, en types primitifs. Et un {@code @Inject}
 * compose avec les autres mods comme un {@code @WrapOperation}.
 *
 * <p>La cible {@code @At} nomme son propriétaire : elle est traduite par
 * {@code REFMAP_REMAP}. Le sélecteur {@code method=}, lui, a son entrée de
 * refmap.
 */
@Mixin(targets = "net.minecraft.client.render.Camera")
public abstract class CameraFreelookMixin1211 {

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V",
                     ordinal = 1, shift = At.Shift.AFTER),
            require = 0)
    private void la$applyFreelookOffset(CallbackInfo ci) {
        try {
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());
            CameraAccessor1211 camera = (CameraAccessor1211) (Object) this;
            Object result = VanillaHookRegistry.dispatchValue(HookPoint.FREELOOK_CAMERA_ROTATION_OFFSET,
                new float[]{camera.la$yaw(), camera.la$pitch()});
            if (result instanceof float[]) {
                float[] adjusted = (float[]) result;
                camera.la$setRotation(adjusted[0], adjusted[1]);
            }
        } catch (Throwable t) {
            LauncherLog.err("[CameraFreelookMixin1211] " + t);
        }
    }
}
