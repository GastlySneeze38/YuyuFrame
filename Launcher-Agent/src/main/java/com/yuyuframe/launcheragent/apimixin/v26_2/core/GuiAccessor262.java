package com.yuyuframe.launcheragent.apimixin.v26_2.core;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accès au {@code Gui} de la 26.2 — qui n'est plus le HUD mais le
 * gestionnaire d'écrans : {@code screen} et {@code setScreen} ont quitté
 * {@code Minecraft} pour lui, et le HUD vit dans son champ {@code hud}
 * (voir {@link HudAccessor262}). Noms vérifiés par javap sur le jar client
 * 26.2 : {@code private Screen screen}, {@code public final Hud hud},
 * {@code public void setScreen(Screen)}.
 *
 * <p>Accessors même sur des membres publics : choix du projet (compatibilité
 * avec les autres mods et entre versions), voir {@code MinecraftAccessor262}.
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public interface GuiAccessor262 {

    @Accessor("screen")
    Screen la$screen();

    @Accessor("hud")
    Hud la$hud();

    @Invoker("setScreen")
    void la$setScreen(Screen screen);
}
