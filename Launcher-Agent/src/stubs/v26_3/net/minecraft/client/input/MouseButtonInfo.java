package net.minecraft.client.input;

/**
 * Stub compile-only 26.3 — record {@code MouseButtonInfo(button, modifiers)},
 * reçu par {@code MouseHandler.onButton}. En 26.3, {@code button()} est un
 * index SDL ({@code SDL_BUTTON_LEFT} = 1, {@code MIDDLE} = 2, {@code RIGHT} =
 * 3). Relevé par javap.
 */
public final class MouseButtonInfo {

    private MouseButtonInfo() {
    }

    public int button() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
