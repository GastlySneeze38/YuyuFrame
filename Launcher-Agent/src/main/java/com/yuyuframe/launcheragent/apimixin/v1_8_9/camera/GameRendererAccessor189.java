package com.yuyuframe.launcheragent.apimixin.v1_8_9.camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Multiplicateurs de champ de vision liés au déplacement — champs PRIVÉS
 * {@code movementFovMultiplier}/{@code lastMovementFovMultiplier}
 * ({@code bfk.x}/{@code bfk.y}, vérifiés javap). Écrits par
 * {@code FovMovementEffectMixin189} ; cible en nom Yarn, traduite par le refmap.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public interface GameRendererAccessor189 {

    @Accessor("movementFovMultiplier")
    void la$setMovementFovMultiplier(float value);

    @Accessor("lastMovementFovMultiplier")
    void la$setLastMovementFovMultiplier(float value);
}
