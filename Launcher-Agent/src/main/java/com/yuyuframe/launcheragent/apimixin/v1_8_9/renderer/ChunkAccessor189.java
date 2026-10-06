package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Drapeau PRIVÉ « ce chunk contient des entités » — champ
 * {@code containsEntities} ({@code amy.r}, vérifié sur le jar). Évite de
 * parcourir les seize listes d'entités d'un chunk qui n'en a aucune.
 */
@Mixin(targets = "net.minecraft.world.chunk.Chunk")
public interface ChunkAccessor189 {

    @Accessor("containsEntities")
    boolean la$containsEntities();
}
