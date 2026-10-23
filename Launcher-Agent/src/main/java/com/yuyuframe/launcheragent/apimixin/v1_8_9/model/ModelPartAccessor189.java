package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * {@code ModelPart} 1.8.9 ({@code bct}, champs publics, javap) : point de
 * rotation ({@code pivotX/Y/Z} = {@code c/d/e}), angles ({@code posX/Y/Z} =
 * {@code f/g/h}, mal nommés par Yarn : ce sont bien des angles en radians),
 * décalage en blocs ({@code offsetX/Y/Z} = {@code o/p/q}), pavés
 * ({@code cuboids} = {@code l}) et enfants ({@code modelList} = {@code m}).
 * Cible en nom Yarn, traduite par le refmap ({@code LauncherMixinService}).
 */
@Mixin(targets = "net.minecraft.client.render.model.ModelPart")
public interface ModelPartAccessor189 {

    @Accessor("pivotX")
    float la$pivotX();

    @Accessor("pivotY")
    float la$pivotY();

    @Accessor("pivotZ")
    float la$pivotZ();

    @Accessor("posX")
    float la$angleX();

    @Accessor("posY")
    float la$angleY();

    @Accessor("posZ")
    float la$angleZ();

    @Accessor("offsetX")
    float la$offsetX();

    @Accessor("offsetY")
    float la$offsetY();

    @Accessor("offsetZ")
    float la$offsetZ();

    /** {@code List<ModelBox>}. */
    @Accessor("cuboids")
    List<?> la$cuboids();

    /** {@code List<ModelPart>}, {@code null} sans enfant. */
    @Accessor("modelList")
    List<?> la$children();
}
