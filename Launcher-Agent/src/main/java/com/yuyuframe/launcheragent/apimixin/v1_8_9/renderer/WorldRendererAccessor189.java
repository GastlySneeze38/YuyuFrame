package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Champs PRIVÉS du {@code WorldRenderer} ({@code bfr}) dont le renderer de
 * terrain a besoin — vérifiés sur le jar :
 * <ul>
 *   <li>{@code renderDistance} ({@code bfr.P}) : la distance pour laquelle
 *       vanilla a construit sa grille, comparée au réglage pour savoir s'il
 *       faut recharger ;</li>
 *   <li>{@code hiddenEntityCount} ({@code bfr.S}) : compteur d'entités dessinées
 *       de l'écran F3 (le nom Yarn est trompeur) ;</li>
 *   <li>{@code visibleChunks} ({@code bfr.m}) : la liste des chunks visibles de
 *       vanilla, à vider quand c'est notre renderer qui prend la main.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public interface WorldRendererAccessor189 {

    @Accessor("renderDistance")
    int la$getRenderDistance();

    @Accessor("hiddenEntityCount")
    int la$getHiddenEntityCount();

    @Accessor("hiddenEntityCount")
    void la$setHiddenEntityCount(int value);

    @Accessor("visibleChunks")
    List<Object> la$visibleChunks();
}
