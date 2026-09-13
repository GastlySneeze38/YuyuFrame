package net.minecraft.client.input;

/**
 * Stub compile-only — remplace {@code KeyInput} sur MC 26.1+ (vérifié par
 * javap sur le jar client 26.1.2 réel : {@code GuiEventListener.keyPressed(
 * KeyEvent)}, un seul paramètre). Voir ScreenStubPatcher pour le patch
 * bytecode et UiScreenBase pour le pourquoi (nouvelle surcharge keyPressed).
 *
 * Accesseur vérifié par javap : key() — même nom que l'ancien {@code KeyInput}.
 */
public abstract class KeyEvent {
    public int key() { return 0; }
}
