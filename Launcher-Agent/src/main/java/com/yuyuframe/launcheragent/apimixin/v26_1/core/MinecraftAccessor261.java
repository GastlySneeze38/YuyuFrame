package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import net.minecraft.client.Window;
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

    /** Ajouté pour {@code FpsModule} — champ STATIC (accesseur Sponge Mixin valide sur un champ statique, appelable depuis n'importe quelle instance). */
    @Accessor("fps")
    int la$fps();

    /** Ajouté pour {@code ChatEnhancementsModule} (pseudo local, détection de mention). */
    @Accessor("user")
    User la$user();
}
