package com.yuyuframe.launcheragent.apimixin.v26_1_2.screen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Porte (simplifié) {@code MouseHandlerMixin#invokeMouseScrollEvents}
 * (fabric-screen-api-v1, voir mixinapi/26.1.2) vers {@link HookPoint#MOUSE_SCROLL}
 * — comble la carence scroll notée en Phase 5.6. L'original Fabric a un
 * triptyque allow/before/after avec annulation possible (retour {@code true}
 * = consommé) ; ici, notification simple, la valeur de retour vanilla
 * n'est jamais modifiée (retour toujours celui d'{@code operation.call}).
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
abstract class MouseScrollMixin261 {

    @WrapOperation(method = "onScroll",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
    private boolean la$dispatchMouseScroll(Screen screen, double mouseX, double mouseY, double horizontalAmount, double verticalAmount, Operation<Boolean> operation) {
        if (screen != null) {
            VanillaHookRegistry.dispatch(HookPoint.MOUSE_SCROLL, screen);
        }
        return operation.call(screen, mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
