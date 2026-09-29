package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accès au {@code Hud} de la 26.2 (l'ancien {@code Gui} de la 26.1). Noms
 * vérifiés par javap sur le jar client 26.2 : {@code private final
 * ChatComponent chat}, {@code public boolean isHidden()}.
 *
 * <p>{@code isHidden()} remplace le champ {@code Options.hideGui} de la 26.1
 * (retiré en 26.2) : l'état « HUD masqué » (F1) est désormais porté par le HUD
 * lui-même.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
public interface HudAccessor263 {

    @Accessor("chat")
    ChatComponent la$chat();

    @Invoker("isHidden")
    boolean la$isHidden();
}
