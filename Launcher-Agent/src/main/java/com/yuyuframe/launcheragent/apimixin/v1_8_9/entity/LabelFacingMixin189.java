package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * À la sortie de {@code EntityRenderDispatcher.updateCamera}
 * ({@code biu.a(Ladm;Lavn;Lpk;Lpk;Lavh;F)V}), une fois par image : corrige
 * l'orientation que vanilla vient de poser. Voir {@link LabelFacing189}.
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderDispatcher")
public abstract class LabelFacingMixin189 {

    @Inject(method = "updateCamera(Lnet/minecraft/world/World;Lnet/minecraft/client/font/TextRenderer;"
            + "Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;Lnet/minecraft/client/option/GameOptions;F)V",
            at = @At("RETURN"), require = 0)
    private void la$faceCamera(@Coerce Object world, @Coerce Object textRenderer, @Coerce Object camera,
                               @Coerce Object target, @Coerce Object options, float tickDelta, CallbackInfo ci) {
        LabelFacing189.afterVanilla(this);
    }
}
