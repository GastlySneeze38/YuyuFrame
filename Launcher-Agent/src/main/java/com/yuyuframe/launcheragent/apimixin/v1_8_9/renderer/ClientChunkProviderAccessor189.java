package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Liste PRIVÉE des chunks chargés côté client — champ {@code chunks}
 * ({@code bcz.d}, vérifié sur le jar). Parcourue pour rassembler les entités à
 * dessiner (voir {@code EntityGatherer}).
 */
@Mixin(targets = "net.minecraft.world.chunk.ClientChunkProvider")
public interface ClientChunkProviderAccessor189 {

    @Accessor("chunks")
    List<Object> la$chunks();
}
