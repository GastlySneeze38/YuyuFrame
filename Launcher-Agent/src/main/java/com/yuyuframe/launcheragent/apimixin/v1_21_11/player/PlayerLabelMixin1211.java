package com.yuyuframe.launcheragent.apimixin.v1_21_11.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Le logo sur l'étiquette d'un joueur, en 1.21.11 — les deux temps sont sur la
 * même classe, {@code PlayerEntityRenderer} ({@code icd}) :
 * <ul>
 *   <li>à la sortie de {@code updateRenderState}, où l'on tient le joueur et
 *       l'état qui sera dessiné : la place du logo est mise devant le pseudo ;</li>
 *   <li>en tête de {@code renderLabelIfPresent} : le logo est soumis.</li>
 * </ul>
 * Voir {@link PlayerBadge1211}. Pendant d'{@code AvatarNameMixin261} et de
 * {@code NameTagLogoMixin261}.
 */
@Mixin(targets = "net.minecraft.client.render.entity.PlayerEntityRenderer")
public abstract class PlayerLabelMixin1211 {

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void la$reserveLogo(@Coerce Object entity, @Coerce Object state, float tickDelta, CallbackInfo ci) {
        PlayerBadge1211.afterExtract(entity, state);
    }

    @Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"), require = 0)
    private void la$submitLogo(@Coerce Object state, @Coerce Object matrices, @Coerce Object queue, @Coerce Object camera,
                               CallbackInfo ci) {
        PlayerBadge1211.beforeLabel(state, matrices, queue, camera);
    }
}
