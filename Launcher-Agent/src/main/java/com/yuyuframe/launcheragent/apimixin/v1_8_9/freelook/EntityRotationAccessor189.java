package com.yuyuframe.launcheragent.apimixin.v1_8_9.freelook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Rotation d'une entité 1.8.9, pour le freelook — champs {@code yaw}/
 * {@code pitch}/{@code prevYaw}/{@code prevPitch} ({@code pk.y/z/A/B}) et
 * {@code increaseTransforms(FF)V} ({@code pk.c}), vérifiés dans Yarn legacy et
 * au javap. Primitifs seulement ; noms traduits par des entrées de refmap
 * FIELD et INVOKER.
 */
@Mixin(targets = "net.minecraft.entity.Entity")
public interface EntityRotationAccessor189 {

    @Accessor("yaw")
    float la$yaw();

    @Accessor("yaw")
    void la$setYaw(float value);

    @Accessor("pitch")
    float la$pitch();

    @Accessor("pitch")
    void la$setPitch(float value);

    @Accessor("prevYaw")
    float la$prevYaw();

    @Accessor("prevYaw")
    void la$setPrevYaw(float value);

    @Accessor("prevPitch")
    float la$prevPitch();

    @Accessor("prevPitch")
    void la$setPrevPitch(float value);

    @Invoker("increaseTransforms")
    void la$increaseTransforms(float yaw, float pitch);
}
