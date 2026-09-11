package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link HookPoint#MOUSE_SCROLL} sur 1.21.11 — pendant de {@code MouseScrollMixin261}.
 *
 * <pre>
 *   26.1.2 : MouseHandler.onScroll → Screen.mouseScrolled(DDDD)Z
 *   1.21.11 : Mouse.onMouseScroll  → Screen.mouseScrolled(DDDD)Z  (gsb.a(DDDD)Z, javap)
 * </pre>
 *
 * Même injecteur qu'en 26.1.2 : le contexte est l'ÉCRAN, qui n'est ici que le
 * receveur de l'appel — seul un {@code @WrapOperation} le voit. Receveur en
 * {@code Object} : même pari que {@code MouseHandlerFreelookMixin1211}.
 * Cible héritée ({@code Element.mouseScrolled} dans Yarn) : voir
 * {@link KeyboardKeyMixin1211}.
 */
@Mixin(targets = "net.minecraft.client.Mouse")
public abstract class MouseScrollMixin1211 {

    @WrapOperation(method = "onMouseScroll(JDD)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;mouseScrolled(DDDD)Z"),
        require = 0)
    private boolean la$dispatchMouseScroll(Object screen, double mouseX, double mouseY,
                                           double horizontalAmount, double verticalAmount,
                                           Operation<Boolean> operation) {
        if (screen != null) {
            VanillaHookRegistry.dispatch(HookPoint.MOUSE_SCROLL, screen);
        }
        return operation.call(screen, mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
