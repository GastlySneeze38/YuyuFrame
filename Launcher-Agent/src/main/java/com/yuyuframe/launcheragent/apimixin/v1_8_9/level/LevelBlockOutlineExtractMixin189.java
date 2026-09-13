package com.yuyuframe.launcheragent.apimixin.v1_8_9.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#LEVEL_BLOCK_OUTLINE_EXTRACT} sur 1.8.9 —
 * {@code WorldRenderer.drawBlockOutline(PlayerEntity, BlockHitResult, int, float)},
 * RETURN. En 1.8.9 le contour est dessiné directement (pas d'extraction d'état) ;
 * {@code ctx} = le joueur, là où la 1.21.11 passe la caméra.
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public abstract class LevelBlockOutlineExtractMixin189 {

    @Inject(method = "drawBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;IF)V",
            at = @At("RETURN"), require = 0)
    private void la$dispatchBlockOutline(@Coerce Object player, @Coerce Object hit, int mode, float tickDelta,
                                         CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT, player);
    }
}
