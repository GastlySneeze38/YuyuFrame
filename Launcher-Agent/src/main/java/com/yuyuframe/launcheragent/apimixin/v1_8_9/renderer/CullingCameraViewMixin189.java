package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.celeritas.v1_8_9.RendererHooks189;
import com.yuyuframe.launcheragent.celeritas.viewport.Viewport;
import com.yuyuframe.launcheragent.celeritas.viewport.ViewportProvider;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fait de la vue de caméra de vanilla ({@code CullingCameraView}, {@code bic})
 * une source de volume de vue pour le moteur — portage de
 * {@code FrustumCullerMixin} de l'adaptateur {@code ornithe} de Celeritas.
 *
 * <p>La position de caméra vient de {@link CullingCameraViewAccessor189} ; les
 * plans de coupe sont relus par {@link RendererHooks189#createViewport}, pour
 * ne nommer ici aucun type du jeu.
 */
@Mixin(targets = "net.minecraft.client.render.CullingCameraView")
public abstract class CullingCameraViewMixin189 implements ViewportProvider {

    @Override
    public Viewport sodium$createViewport() {
        CullingCameraViewAccessor189 self = (CullingCameraViewAccessor189) (Object) this;
        return RendererHooks189.createViewport(self.la$getX(), self.la$getY(), self.la$getZ());
    }
}
