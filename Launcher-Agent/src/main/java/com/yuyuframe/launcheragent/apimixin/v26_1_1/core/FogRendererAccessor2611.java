package com.yuyuframe.launcheragent.apimixin.v26_1_1.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code FogRenderer.fogEnabled} — remplace la
 * résolution réflexive de {@code NoFogModule} (2026-08-27).
 *
 * <p>Ce que la réflexion devait faire à la main, et que l'accessor rend
 * inutile : la classe a changé DEUX fois de nom Yarn
 * ({@code render/BackgroundRenderer} en 1.16.5-1.21.4, puis
 * {@code render/fog/FogRenderer}), et le nom réel Mojang de 26.1.2
 * ({@code net.minecraft.client.renderer.fog.FogRenderer}) diffère encore des
 * deux — {@code NoFogModule} enchaînait donc trois tentatives de résolution
 * avant de trouver le champ. Ici le nom réel est écrit une seule fois, et un
 * autre bracket demandera son propre accessor plutôt qu'un quatrième repli.
 *
 * <p><b>{@code static} REQUIS</b> : le champ l'est (c'est ce que faisait
 * l'ancien {@code Field.set(null, ...)}), et Sponge Mixin exige que
 * l'accesseur ait la même staticité que sa cible — sans quoi il avertit à
 * chaque lancement (<i>should be static as its target is</i>) et l'accès ne
 * fonctionne pas. Même idiome que {@link MinecraftAccessor2611#la$fps()}.
 *
 * <p>Les corps ne sont jamais exécutés — Mixin les remplace au tissage. Ils
 * lèvent plutôt que de renvoyer une valeur silencieusement fausse, pour que
 * le cas « mixin non tissé » soit bruyant.
 *
 * <p>Pas de stub {@code FogRenderer} nécessaire : la cible est désignée par
 * son nom en chaîne ({@code targets}), et l'interface ne manipule qu'un
 * {@code boolean} — même approche que les mixins de {@code v26_1_1.fog}.
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.FogRenderer")
public interface FogRendererAccessor2611 {

    @Accessor("fogEnabled")
    static boolean la$fogEnabled() { throw new AssertionError("FogRendererAccessor2611 non tissé"); }

    @Accessor("fogEnabled")
    static void la$setFogEnabled(boolean enabled) { throw new AssertionError("FogRendererAccessor2611 non tissé"); }
}
