package com.yuyuframe.launcheragent.apimixin.v26_3.input;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.GlobalUiRenderBridge263;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Souris du poller d'entrée — 26.3 (SDL3). Même principe que
 * {@link KeyboardInputMixin263} : les événements SDL sont relayés par le jeu à
 * {@code MouseHandler.onButton(fenêtre, MouseButtonInfo, action)} et
 * {@code onScroll(fenêtre, dx, dy)} (javap), lus ici au HEAD.
 *
 * <p>Molette : comme le callback GLFW chaîné, le scroll vanilla n'est PAS
 * transmis tant qu'un module le consomme ({@link UiInputPoller#suppressVanillaScroll},
 * le zoom) — sinon zoomer changerait aussi l'objet en main. Sous GLFW on
 * n'appelait pas le callback précédent ; ici on annule {@code onScroll}.
 *
 * <p>Infrastructure du poller, pas un HookPoint : jamais gaté.
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
abstract class MouseInputMixin263 {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void la$recordButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        UiInputPoller poller = GlobalUiRenderBridge263.inputPoller;
        if (!(poller instanceof UiInputPollerModern) || info == null) return;
        int button = SdlKeys263.buttonToGlfw(info.button());
        if (button >= 0) ((UiInputPollerModern) poller).onMouseButtonEvent(button, action != 0);
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void la$recordScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        UiInputPoller poller = GlobalUiRenderBridge263.inputPoller;
        if (!(poller instanceof UiInputPollerModern)) return;
        ((UiInputPollerModern) poller).onScrollEvent(vertical);
        if (UiInputPoller.suppressVanillaScroll) ci.cancel();
    }
}
