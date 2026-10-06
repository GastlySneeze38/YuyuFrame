package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Champs PRIVÉS du {@code WorldRenderer} ({@code bfr}) dont le renderer de
 * terrain a besoin — vérifiés sur le jar :
 * <ul>
 *   <li>{@code hiddenEntityCount} ({@code bfr.S}) : compteur d'entités dessinées
 *       de l'écran F3 (le nom Yarn est trompeur) ;</li>
 *   <li>{@code visibleChunks} ({@code bfr.m}) : la liste des chunks visibles de
 *       vanilla, à vider quand c'est notre renderer qui prend la main.</li>
 * </ul>
 *
 * <p><b>Pas d'accessor sur {@code renderDistance} ({@code bfr.P})</b> : Mixin
 * retrouve la cible d'un {@code @Accessor} sans tenir compte de la casse, et
 * {@code bfr} a aussi un {@code p} de même type, déclaré avant — c'est lui
 * qui était lu (2026-10-06). {@code S} et {@code m} ont bien des homonymes
 * ({@code s}, {@code M}), mais d'un autre type, donc sans ambiguïté.
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public interface WorldRendererAccessor189 {

    @Accessor("hiddenEntityCount")
    int la$getHiddenEntityCount();

    @Accessor("hiddenEntityCount")
    void la$setHiddenEntityCount(int value);

    @Accessor("visibleChunks")
    List<Object> la$visibleChunks();
}
