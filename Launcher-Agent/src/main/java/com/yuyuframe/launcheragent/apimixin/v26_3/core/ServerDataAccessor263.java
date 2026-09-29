package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code ServerData.ip} et {@code name} — l'adresse du
 * serveur courant, clé des mots de passe de {@code MacroModule}.
 *
 * <p>Les deux champs sont PUBLICS sur 26.1.2 : un accès direct compilerait.
 * Ils passent quand même par un accessor, comme {@code Minecraft.player} et
 * {@code DataComponents.FOOD} — c'est la norme du projet, et ce qui rend le
 * portage multiversion mécanique (voir {@code MinecraftAccessor263}).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerData")
public interface ServerDataAccessor263 {
    /** Adresse telle que saisie dans la liste des serveurs ({@code jouer.exemple.fr:25565}). */
    @Accessor("ip")
    String la$ip();

    /** Nom affiché dans la liste — sert à l'écran d'édition, pas à l'identification. */
    @Accessor("name")
    String la$name();
}
