package org.lwjgl.sdl;

import java.nio.FloatBuffer;

/**
 * Stub compile-only — LWJGL 3.4.3 {@code lwjgl-sdl}, bibliothèque de la 26.3
 * (le jeu y a remplacé GLFW par SDL3). Signature relevée par javap sur
 * {@code lwjgl-sdl-3.4.3.jar} ; seule la fonction utilisée par
 * {@code SdlNativeInput263} figure ici.
 */
public final class SDLMouse {

    private SDLMouse() {
    }

    /** Position du curseur relative à la fenêtre qui a le focus souris, en coordonnées fenêtre. */
    public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
