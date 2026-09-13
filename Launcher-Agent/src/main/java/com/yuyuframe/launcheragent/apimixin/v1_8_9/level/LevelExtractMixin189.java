package com.yuyuframe.launcheragent.apimixin.v1_8_9.level;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#LEVEL_EXTRACT} sur 1.8.9 — fin du rendu du monde.
 *
 * <p>La 1.8.9 rend le monde depuis {@code GameRenderer.renderWorld(float, long)},
 * qui appelle {@code renderWorld(int, float, long)} une fois par passe
 * (deux en anaglyphe) : la méthode sans passe est visée pour un seul dispatch
 * par image. {@code ctx} = {@code null} (pas d'objet caméra sur cette version).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class LevelExtractMixin189 {

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"), require = 0)
    private void la$dispatchLevelExtract(float tickDelta, long limitTime, CallbackInfo ci) {
        VanillaHookRegistry.dispatch(HookPoint.LEVEL_EXTRACT, null);
    }
}
