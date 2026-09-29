package net.minecraft.client.input;

/**
 * Stub compile-only — remplace {@code Click} sur MC 26.1+ (vérifié par javap
 * sur le jar client 26.1.2 réel : {@code GuiEventListener.mouseClicked(
 * MouseButtonEvent, boolean)}, l'interface {@code Element} ayant elle-même
 * été renommée {@code GuiEventListener} et déplacée dans
 * {@code net.minecraft.client.gui.components.events}). Voir ScreenStubPatcher
 * pour le patch bytecode et UiScreenBase pour le pourquoi (nouvelle
 * surcharge mouseClicked) — même mécanisme que {@code Click}/{@code KeyInput}
 * pour le bracket 1.21.11.
 *
 * Accesseurs vérifiés par javap : x()/y()/button() — mêmes noms que
 * l'ancien {@code Click}, aucun renommage nécessaire pour ceux-là.
 */
public abstract class MouseButtonEvent {
    public double x() { return 0; }
    public double y() { return 0; }
    public int button() { return 0; }
}
