package com.yuyuframe.launcheragent.apimixin.v26_1_2.model;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code ModelPart.Polygon.vertices} (record, champ privé), vérifié par javap sur le jar 26.1.2. */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart$Polygon")
public interface ModelPartPolygonAccessor261 {

    @Accessor("vertices")
    ModelPart.Vertex[] la$vertices();
}
