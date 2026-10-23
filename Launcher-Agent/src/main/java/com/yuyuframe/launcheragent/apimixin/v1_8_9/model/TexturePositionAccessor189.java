package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Un sommet de face 1.8.9 ({@code bcg}, champs publics, javap) : position
 * ({@code a}, en pixels du modèle) et coordonnées de texture ({@code b},
 * {@code c}), déjà ramenées de 0 à 1 par le jeu. Cible en nom Yarn, traduite
 * par le refmap.
 */
@Mixin(targets = "net.minecraft.client.util.math.TexturePosition")
public interface TexturePositionAccessor189 {

    @Accessor("position")
    Vec3d la$position();

    @Accessor("u")
    float la$u();

    @Accessor("v")
    float la$v();
}
