package org.lwjgl.sdl;

import java.nio.IntBuffer;

/**
 * Stub compile-only — LWJGL 3.4.3 {@code lwjgl-sdl} (voir {@link SDLMouse}).
 * Signatures relevées par javap sur {@code lwjgl-sdl-3.4.3.jar}.
 */
public final class SDLVideo {

    private SDLVideo() {
    }

    /** Taille en coordonnées fenêtre (pendant de {@code glfwGetWindowSize}). */
    public static boolean SDL_GetWindowSize(long window, IntBuffer w, IntBuffer h) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Taille en pixels (pendant de {@code glfwGetFramebufferSize}). */
    public static boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
