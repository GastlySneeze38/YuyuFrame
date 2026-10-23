package com.yuyuframe.launcheragent.apimixin.v26_3.model;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code LayerDefinition.bakeRoot()} (public), vérifié par javap sur le jar 26.3. */
@Mixin(targets = "net.minecraft.client.model.geom.builders.LayerDefinition")
public interface LayerDefinitionAccessor263 {

    @Invoker("bakeRoot")
    ModelPart la$bakeRoot();
}
