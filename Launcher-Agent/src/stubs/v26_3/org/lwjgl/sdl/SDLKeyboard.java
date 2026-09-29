package org.lwjgl.sdl;

import java.nio.ByteBuffer;

/**
 * Stub compile-only — LWJGL 3.4.3 {@code lwjgl-sdl} (voir {@link SDLMouse}).
 * Signatures relevées par javap sur {@code lwjgl-sdl-3.4.3.jar} ; ce sont
 * aussi celles qu'emploie le jeu ({@code InputConstants.isKeyDown},
 * {@code InputConstants$Type}).
 */
public final class SDLKeyboard {

    private SDLKeyboard() {
    }

    /** État du clavier, un octet par scancode (non nul = enfoncée). */
    public static ByteBuffer SDL_GetKeyboardState() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static int SDL_GetKeyFromScancode(int scancode, short modstate, boolean keyEvent) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static String SDL_GetKeyName(int key) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
