package net.minecraft.client.render;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code avc}, MCP {@code RenderHelper}).
 *
 * <p>{@code enable()} ({@code avc.c}) = éclairage GUI des items, celui qu'appelle
 * la hotbar vanilla (javap {@code InGameHud.render}) ; {@code disable()}
 * ({@code avc.a}) le coupe.
 */
public final class DiffuseLighting {

    private DiffuseLighting() {
    }

    public static void enable() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void disable() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
