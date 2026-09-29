package com.yuyuframe.launcheragent.apimixin.v26_1_1.screen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte (simplifié) {@code KeyboardHandlerMixin#invokeKeyPressedEvents}
 * (fabric-screen-api-v1, voir mixinapi/26.1.2) vers {@link HookPoint#KEYBOARD_KEY}
 * — voir {@link MouseScrollMixin2611} pour la même simplification (notification
 * seule, pas d'annulation).
 */
@Mixin(targets = "net.minecraft.client.KeyboardHandler")
abstract class KeyboardKeyMixin2611 {

    @WrapOperation(method = "keyPress",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z"))
    private boolean la$dispatchKeyPress(Screen screen, KeyEvent event, Operation<Boolean> operation) {
        if (screen != null) {
            VanillaHookRegistry.dispatch(HookPoint.KEYBOARD_KEY, event);
        }
        return operation.call(screen, event);
    }
}
