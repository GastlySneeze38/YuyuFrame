package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour les clés de composants d'objet — {@code
 * DataComponents.FOOD} pour l'instant, lu par {@code SaturationModule}.
 *
 * <p>Champ {@code public static final} sur 26.1.2 : un accès direct
 * compilerait. Il passe quand même par un accessor, sur consigne explicite —
 * c'est la norme du projet pour TOUT accès à l'état du jeu, champ public
 * inclus, parce que la portabilité multiversion vient de ce que tous les
 * accès traversent une seule interface par bracket (même raisonnement que
 * {@code MinecraftAccessor263} et {@code RenderPipelinesAccessor263}).
 *
 * <p>{@code static} requis : l'idiome Sponge Mixin pour un champ statique.
 */
@Mixin(targets = "net.minecraft.core.component.DataComponents")
public interface DataComponentsAccessor263 {
    @Accessor("FOOD")
    static DataComponentType<FoodProperties> la$food() { throw new AssertionError("DataComponentsAccessor263 non tissé"); }

    /** Enchantements d'un objet — {@code SaturationModule} y lit le niveau de « lunge », seul enchantement du jeu à consommer de la faim. */
    @Accessor("ENCHANTMENTS")
    static DataComponentType<ItemEnchantments> la$enchantments() { throw new AssertionError("DataComponentsAccessor263 non tissé"); }
}
