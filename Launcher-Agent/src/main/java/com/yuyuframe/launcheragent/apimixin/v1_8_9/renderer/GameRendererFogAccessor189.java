package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Couleur du brouillard, champs PRIVÉS {@code fogRed/fogGreen/fogBlue}
 * ({@code bfk.Q/R/S}, vérifiés sur le jar). Lus par
 * {@link FogColorCaptureMixin189}.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public interface GameRendererFogAccessor189 {

    @Accessor("fogRed")
    float la$getFogRed();

    @Accessor("fogGreen")
    float la$getFogGreen();

    @Accessor("fogBlue")
    float la$getFogBlue();
}
