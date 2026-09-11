package com.yuyuframe.launcheragent.apimixin.v1_21_11.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#LEVEL_EXTRACT} sur 1.21.11 — APPROCHANT de
 * {@code LevelExtractMixin261}.
 *
 * <p>26.1.2 : {@code LevelRenderer.extractLevel(DeltaTracker, Camera, float)},
 * RETURN — l'état du monde est extrait, pas encore rendu. 1.21.11 n'a pas de
 * méthode d'extraction séparée ; le plus proche est le RETURN de
 * {@code WorldRenderer.render(...)} (Mojang {@code renderLevel}, officiel
 * {@code hoh.a(fyt,gez,Z,ger,…)}), avec la même caméra en contexte. Aucun
 * module ne réclame ce HookPoint aujourd'hui.
 *
 * <p>Tous les paramètres objets en {@link Coerce} {@code Object} — y compris
 * les types non obfusqués (JOML, {@code GpuBufferSlice}), pour ne dépendre
 * d'aucune classe de compilation.
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class LevelExtractMixin1211 {

    @Inject(method = "render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("RETURN"), require = 0)
    private void la$dispatchLevelExtract(@Coerce Object allocator, @Coerce Object tickCounter, boolean renderBlockOutline,
                                         @Coerce Object camera, @Coerce Object positionMatrix, @Coerce Object matrix4f,
                                         @Coerce Object projectionMatrix, @Coerce Object fogBuffer,
                                         @Coerce Object fogColor, boolean renderSky, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_EXTRACT, camera);
    }
}
