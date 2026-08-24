package net.minecraft.client;

/**
 * Stub compile-only (26.1+) — juste assez pour typer le champ {@code
 * Minecraft.window} (voir {@code MinecraftAccessor261}) et appeler {@code
 * handle()} directement (méthode publique, plus besoin de réflexion — voir
 * l'ancienne implémentation de {@code GlobalUiRenderBridge261}).
 */
public abstract class Window {
    public long handle() { return 0L; }
    public int getGuiScaledWidth() { return 0; }
}
