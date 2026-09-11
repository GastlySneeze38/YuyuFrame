package com.yuyuframe.launcheragent.apimixin.v1_21_11.freelook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Rappel de {@code Entity.changeLookDirection(DD)V} — pour
 * {@link MouseHandlerFreelookMixin1211}, dont le {@code @Redirect} remplace
 * l'appel et doit donc le refaire lui-même quand le freelook ne l'intercepte
 * pas. Primitifs seulement ; nom traduit par une entrée de refmap INVOKER.
 */
@Mixin(targets = "net.minecraft.entity.Entity")
public interface EntityInvoker1211 {

    @Invoker("changeLookDirection")
    void la$changeLookDirection(double cursorDeltaX, double cursorDeltaY);
}
