package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog.GLStateManagerFogService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relève la couleur du brouillard que vanilla vient de calculer — portage de
 * {@code GameRendererMixin} de l'adaptateur {@code ornithe} de Celeritas.
 *
 * <p>Distance et densité se relisent dans l'état OpenGL, mais pas la couleur :
 * le shader de terrain la prend ici. {@code updateFog(F)} ({@code bfk.i}),
 * vérifiée sur le jar ; les trois champs passent par
 * {@link GameRendererFogAccessor189}.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class FogColorCaptureMixin189 {

    @Inject(method = "updateFog(F)V", at = @At("RETURN"), require = 0)
    private void la$captureFogColor(float tickDelta, CallbackInfo ci) {
        GameRendererFogAccessor189 self = (GameRendererFogAccessor189) (Object) this;
        GLStateManagerFogService.fogColorRed = self.la$getFogRed();
        GLStateManagerFogService.fogColorGreen = self.la$getFogGreen();
        GLStateManagerFogService.fogColorBlue = self.la$getFogBlue();
    }
}
