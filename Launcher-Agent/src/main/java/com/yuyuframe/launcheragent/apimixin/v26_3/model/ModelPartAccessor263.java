package com.yuyuframe.launcheragent.apimixin.v26_3.model;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * {@code ModelPart} : pivot, angles au repos, cubes et enfants — de quoi
 * recopier une partie de modèle vanilla dans un {@code UiModel}
 * ({@link VanillaModels263}). Pendant de {@code ModelPartAccessor261} ;
 * noms vérifiés par javap sur le jar 26.3 (identiques à la 26.1.2).
 */
@Mixin(targets = "net.minecraft.client.model.geom.ModelPart")
public interface ModelPartAccessor263 {

    @Accessor("x")
    float la$x();

    @Accessor("y")
    float la$y();

    @Accessor("z")
    float la$z();

    @Accessor("xRot")
    float la$xRot();

    @Accessor("yRot")
    float la$yRot();

    @Accessor("zRot")
    float la$zRot();

    /** {@code List<ModelPart.Cube>}. */
    @Accessor("cubes")
    List<?> la$cubes();

    /** {@code Map<String, ModelPart>}. */
    @Accessor("children")
    Map<String, ?> la$children();
}
