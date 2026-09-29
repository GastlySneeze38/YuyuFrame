package com.mojang.blaze3d.platform;

/**
 * Stub compile-only 26.3 — gestionnaire de saisie de texte SDL
 * ({@code SDL_StartTextInput}/{@code SDL_StopTextInput}), tenu par
 * {@code Minecraft.textInputManager}. SDL ne livre du texte qu'une fois la
 * saisie démarrée ; vanilla ne la démarre que pour ses propres champs.
 * Opaque ici : appelé par {@code TextInputManagerInvoker263}. Relevé par javap.
 */
public class TextInputManager {

    private TextInputManager() {
    }
}
