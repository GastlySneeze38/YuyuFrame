package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Mixin (Sponge) pour 3 champs privés de {@code Minecraft} —
 * remplace la réflexion manuelle de l'ancien {@code
 * mixin.client.v26_1.GlobalUiRenderBridge261} (getDeclaredField +
 * setAccessible, cache manuel de Field) par des getters synthétisés
 * directement par Mixin au tissage : ZÉRO réflexion au runtime pour ces 3
 * accès (audit ROADMAP-agent.md §3.3 — "presque plus de réflexion en
 * 26.1.2, tout passe par apimixin").
 *
 * Non branché pour l'instant (pas dans mixins.launcheragent-26.1.json) —
 * voir {@code core.GlobalUiRenderBridge261} (même dossier) : équivalent
 * apimixin de l'ancien pont, construit en parallèle, le pont mixin/ historique
 * reste actif et inchangé tant que le branchement final n'est pas décidé.
 *
 * Noms de champs vérifiés via javap sur le jar client 26.1.2 réel (voir
 * javadoc historique de {@code mixin.client.v26_1.GlobalUiRenderBridge261}) :
 * {@code window}, {@code screen} (RENOMMÉ depuis "currentScreen" en Yarn),
 * {@code mouseHandler}.
 *
 * Une instance de {@code Minecraft} (obtenue via {@code Minecraft.getInstance()})
 * implémente CETTE interface une fois le Mixin tissé — cast direct
 * {@code (MinecraftAccessor261) mc}, idiome standard Sponge Mixin.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface MinecraftAccessor261 {

    @Accessor("window")
    Window la$window();

    @Accessor("screen")
    Screen la$screen();

    @Accessor("mouseHandler")
    MouseHandler la$mouseHandler();

    /** Ajouté pour {@code FreelookModule} (forçage/restauration de la vue 3e personne) — voir sa javadoc. */
    @Accessor("options")
    Options la$options();

    /**
     * Ajouté pour {@code FpsModule}. {@code static} REQUIS : le champ l'est
     * ({@code private static int fps}, vérifié au javap sur le jar 26.1.2), et
     * Sponge Mixin exige que l'accesseur ait la même staticité que sa cible.
     * Tant que cette méthode était déclarée comme les autres (méthode
     * d'instance), Mixin avertissait à chaque lancement :
     * <pre>@Accessor[FIELD_GETTER]::la$fps()I should be static as its target is</pre>
     *
     * Le corps n'est jamais exécuté — Mixin le remplace au tissage ; il rend
     * juste bruyant le cas où ce mixin ne serait pas appliqué, plutôt que de
     * renvoyer un 0 silencieux et faux. Même idiome que
     * {@code RenderPipelinesAccessor261#la$guiTextured()}.
     *
     * Appel : {@code MinecraftAccessor261.la$fps()}, sans instance.
     */
    @Accessor("fps")
    static int la$fps() { throw new AssertionError("MinecraftAccessor261 non tissé"); }

    /** Ajouté pour {@code ChatEnhancementsModule} (pseudo local, détection de mention). */
    @Accessor("user")
    User la$user();
}
