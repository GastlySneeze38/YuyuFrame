package com.yuyuframe.launcheragent.apimixin.v26_3.model;

import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ChestModel.createSingleBodyLayer()} (statique, public) : la
 * définition du coffre simple, telle que le jeu la construit. Vérifié par
 * javap sur le jar 26.3 ({@code net.minecraft.client.model.object.chest}).
 */
@Mixin(targets = "net.minecraft.client.model.object.chest.ChestModel")
public interface ChestModelAccessor263 {

    @Invoker("createSingleBodyLayer")
    static LayerDefinition la$createSingleBodyLayer() {
        throw new AssertionError("ChestModelAccessor263 non tissé");
    }
}
