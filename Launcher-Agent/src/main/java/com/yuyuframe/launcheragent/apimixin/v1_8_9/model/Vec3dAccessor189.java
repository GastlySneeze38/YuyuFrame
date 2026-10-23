package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Les coordonnées d'un {@code Vec3d} 1.8.9 ({@code aui.a/b/c}, publiques, javap). Cible en nom Yarn, traduite par le refmap. */
@Mixin(targets = "net.minecraft.util.math.Vec3d")
public interface Vec3dAccessor189 {

    @Accessor("x")
    double la$x();

    @Accessor("y")
    double la$y();

    @Accessor("z")
    double la$z();
}
