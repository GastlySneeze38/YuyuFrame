package com.yuyuframe.launcheragent.apimixin.v26_3.model;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ModelPart.Vertex} (record) : position en pixels du modèle, et
 * coordonnées de texture déjà ramenées de 0 à 1 par le jeu. Vérifié par javap
 * sur le jar 26.3.
 */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart$Vertex")
public interface ModelPartVertexAccessor263 {

    @Accessor("x")
    float la$x();

    @Accessor("y")
    float la$y();

    @Accessor("z")
    float la$z();

    @Accessor("u")
    float la$u();

    @Accessor("v")
    float la$v();
}
