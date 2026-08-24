package net.minecraft.client;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code MouseHandlerFreelookMixin261}).
 * {@code isMouseGrabbed()} ajouté pour {@code MinecraftAccessor261}/{@code
 * GlobalUiRenderBridge261} — méthode publique, appel direct sans réflexion
 * (vérifié via javap sur le jar client 26.1.2 réel : {@code public boolean isMouseGrabbed()}).
 */
public abstract class MouseHandler {
    public boolean isMouseGrabbed() { return true; }
}
