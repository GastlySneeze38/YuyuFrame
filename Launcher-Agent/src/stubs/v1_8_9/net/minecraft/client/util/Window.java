package net.minecraft.client.util;

import net.minecraft.client.MinecraftClient;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code avr}).
 *
 * <p>PIÈGE DE NOM : ce n'est PAS la fenêtre GLFW des versions modernes mais
 * l'ancien {@code ScaledResolution} — un objet jetable qu'on construit à la
 * demande depuis le client. {@code getWidth()}/{@code getHeight()} rendent
 * donc les dimensions MISES À L'ÉCHELLE de l'interface (entiers).
 */
public class Window {

    public Window(MinecraftClient client) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getWidth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getScaleFactor() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
