package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code HandledScreen.handleHotbarKeyPressed(int)} ({@code ayl.b(I)Z},
 * protégée) : envoie l'objet survolé dans la case de la barre dont le
 * raccourci porte ce code de touche. Vrai si un raccourci correspondait.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.HandledScreen")
public interface HandledScreenInvoker189 {

    @Invoker("handleHotbarKeyPressed")
    boolean la$handleHotbarKeyPressed(int keyCode);
}
