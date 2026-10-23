package com.yuyuframe.launcheragent.apimixin.v26_1_2.model;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code ModelPart.Cube.polygons} (public final), vérifié par javap sur le jar 26.1.2. */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart$Cube")
public interface ModelPartCubeAccessor261 {

    @Accessor("polygons")
    ModelPart.Polygon[] la$polygons();
}
