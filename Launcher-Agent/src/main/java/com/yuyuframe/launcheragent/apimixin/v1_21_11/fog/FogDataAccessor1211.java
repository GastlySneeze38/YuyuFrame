package com.yuyuframe.launcheragent.apimixin.v1_21_11.fog;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Écriture des distances de brouillard 1.21.11 — pour {@link FogPush1211}.
 *
 * <p>Les quatre champs sont ceux qu'écrit {@code FogPush261} sur 26.1.2 :
 * mêmes noms (Yarn = Mojang ici), classe {@code FogData} obfusquée en
 * {@code igp}. Noms traduits par des entrées de refmap FIELD
 * ({@code LauncherMixinService.REFMAP_ENTRIES}).
 */
@Mixin(targets = "net.minecraft.client.render.fog.FogData")
public interface FogDataAccessor1211 {

    @Accessor("environmentalStart")
    void la$setEnvironmentalStart(float value);

    @Accessor("environmentalEnd")
    void la$setEnvironmentalEnd(float value);

    @Accessor("renderDistanceStart")
    void la$setRenderDistanceStart(float value);

    @Accessor("renderDistanceEnd")
    void la$setRenderDistanceEnd(float value);
}
