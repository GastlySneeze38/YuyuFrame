package com.yuyuframe.launcheragent.apimixin.v26_3.model;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code ModelPart.Cube.polygons} (public final), vérifié par javap sur le jar 26.3. */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart$Cube")
public interface ModelPartCubeAccessor263 {

    @Accessor("polygons")
    ModelPart.Polygon[] la$polygons();
}
