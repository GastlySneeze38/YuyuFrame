package com.yuyuframe.launcheragent.apimixin.v1_8_9.camera;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HookPoint#FOV_MOVEMENT_EFFECT} sur 1.8.9 —
 * {@code GameRenderer.updateMovementFovMultiplier()V} ({@code bfk.l()V}, privée).
 *
 * <p>En fin de mise à jour, remet les deux multiplicateurs à 1 : le champ de
 * vision des options s'applique tel quel, sprint et ralentissement compris.
 * Même effet que l'ancien {@code MixinGameRenderer189}, mais par accessor
 * ({@link GameRendererAccessor189}) au lieu de {@code @Shadow} sur des noms
 * obfusqués.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class FovMovementEffectMixin189 {

    @Inject(method = "updateMovementFovMultiplier()V", at = @At("TAIL"), require = 0)
    private void la$dispatchFovMovementEffect(CallbackInfo ci) {
        if (!VanillaHookRegistry.dispatch(HookPoint.FOV_MOVEMENT_EFFECT, null)) return;
        GameRendererAccessor189 renderer = (GameRendererAccessor189) (Object) this;
        renderer.la$setMovementFovMultiplier(1.0f);
        renderer.la$setLastMovementFovMultiplier(1.0f);
    }
}
