package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import net.minecraft.client.render.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les trois parties du coffre 1.8.9 ({@code baz.a/b/c}, publiques, javap) :
 * couvercle, caisse, loquet. Cible en nom Yarn, traduite par le refmap
 * ({@code LauncherMixinService}).
 */
@Mixin(targets = "net.minecraft.client.render.entity.model.ChestBlockEntityModel")
public interface ChestBlockEntityModelAccessor189 {

    @Accessor("lid")
    ModelPart la$lid();

    @Accessor("base")
    ModelPart la$base();

    @Accessor("lock")
    ModelPart la$lock();
}
