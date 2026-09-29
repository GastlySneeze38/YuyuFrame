package com.yuyuframe.launcheragent.apimixin.v26_3.input;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.GlobalUiRenderBridge263;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clavier du poller d'entrée — 26.3 (SDL3).
 *
 * <p>Sous GLFW, le poller chaînait ses propres callbacks natifs (touche,
 * caractère). SDL3 n'a pas de callbacks : le jeu vide lui-même la file
 * d'événements ({@code SDLEventHandler.pollEvents}) et relaie chaque
 * événement à {@code KeyboardHandler} sur le fil principal (javap) —
 * {@code keyPress(fenêtre, action, KeyEvent)} et {@code textInput(fenêtre,
 * texte)}. On lit ces appels au HEAD, sans rien changer à leur déroulement :
 * c'est l'équivalent exact de l'ancien callback chaîné.
 *
 * <p>{@code action} : 0 = relâchée (SDL_EVENT_KEY_UP), sinon enfoncée ou
 * répétée — même convention {@code action != 0} que sous GLFW.
 * {@code KeyEvent.key()} est un scancode SDL, traduit en code GLFW par
 * {@link SdlKeys263} ; une touche sans équivalent est suivie par scancode.
 *
 * <p>Infrastructure du poller, pas un HookPoint : jamais gaté.
 */
@Mixin(targets = "net.minecraft.client.KeyboardHandler")
abstract class KeyboardInputMixin263 {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
    private void la$recordKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        UiInputPoller poller = GlobalUiRenderBridge263.inputPoller;
        if (!(poller instanceof UiInputPollerModern) || event == null) return;
        int scancode = event.key();
        ((UiInputPollerModern) poller).onKeyEvent(SdlKeys263.toGlfw(scancode), scancode, action != 0);
    }

    @Inject(method = "textInput(JLjava/lang/String;)V", at = @At("HEAD"))
    private void la$recordText(long window, String text, CallbackInfo ci) {
        UiInputPoller poller = GlobalUiRenderBridge263.inputPoller;
        if (poller instanceof UiInputPollerModern) ((UiInputPollerModern) poller).onTextEvent(text);
    }
}
