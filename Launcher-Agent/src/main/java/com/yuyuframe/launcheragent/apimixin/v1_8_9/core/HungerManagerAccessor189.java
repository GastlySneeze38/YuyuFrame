package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Épuisement du joueur — champ PRIVÉ {@code exhaustion} ({@code xg.c}, vérifié
 * javap), sans getter sur cette version. Pendant de
 * {@code HungerManagerAccessor1211} ; cible en nom Yarn, traduite par le
 * refmap ({@code LauncherMixinService}).
 */
@Mixin(targets = "net.minecraft.entity.player.HungerManager")
public interface HungerManagerAccessor189 {

    @Accessor("exhaustion")
    float la$exhaustion();
}
