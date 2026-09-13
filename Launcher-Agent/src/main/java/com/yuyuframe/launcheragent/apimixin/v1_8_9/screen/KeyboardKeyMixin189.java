package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#KEYBOARD_KEY} sur 1.8.9 — touche envoyée à l'écran ouvert.
 *
 * <p>Pendant du site d'appel de la 1.21.11 ({@code Screen.keyPressed} depuis
 * {@code Keyboard.onKey}) : en 1.8.9 c'est {@code Screen.handleKeyboard()} qui
 * lit l'événement LWJGL 2 et appelle {@code keyPressed(char, int)} (javap
 * {@code axu.l()V}). Site d'appel et non la méthode appelée : les écrans
 * redéfinissent {@code keyPressed} sans rappeler la version de base, alors que
 * {@code handleKeyboard} est commune. {@code ctx} = le code de touche LWJGL ({@code Integer}).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public abstract class KeyboardKeyMixin189 {

    @WrapOperation(method = "handleKeyboard()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;keyPressed(CI)V"),
        require = 0)
    private void la$dispatchKeyPress(Object screen, char character, int keyCode, Operation<Void> original) {
        VanillaHookRegistry.dispatch(HookPoint.KEYBOARD_KEY, keyCode);
        original.call(screen, character, keyCode);
    }
}
