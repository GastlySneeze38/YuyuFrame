package com.yuyuframe.launcheragent.apimixin.v26_3.input;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Saisie de texte SDL — 26.3.
 *
 * <p>SDL ne livre de texte ({@code SDL_EVENT_TEXT_INPUT}, relayé par
 * {@code KeyboardHandler.textInput}) qu'entre un {@code SDL_StartTextInput}
 * et un {@code SDL_StopTextInput}. Vanilla ne démarre la saisie que pour ses
 * propres champs ({@code EditBox}…), via ce gestionnaire, qui retient un
 * PROPRIÉTAIRE : {@code stopTextInput(owner)} n'arrête que si c'est bien le
 * propriétaire courant. Les champs de l'agent passent par là pour la même
 * raison — sans quoi la barre de recherche ne recevrait aucun caractère.
 * Méthodes publiques, appelées par invoker (règle de l'agent).
 */
@Mixin(targets = "com.mojang.blaze3d.platform.TextInputManager")
public interface TextInputManagerInvoker263 {

    @Invoker("startTextInput")
    void la$startTextInput(Object owner);

    @Invoker("stopTextInput")
    void la$stopTextInput(Object owner);
}
