package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Position de caméra PRIVÉE de la vue de vanilla — champs {@code x/y/z}
 * ({@code bic.b/c/d}, vérifiés sur le jar), posés par {@code setPos} à chaque
 * image. Par accessor et non par {@code @Shadow} : seuls les accessors passent
 * par notre table de refmap.
 */
@Mixin(targets = "net.minecraft.client.render.CullingCameraView")
public interface CullingCameraViewAccessor189 {

    @Accessor("x")
    double la$getX();

    @Accessor("y")
    double la$getY();

    @Accessor("z")
    double la$getZ();
}
