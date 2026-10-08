package net.minecraft.client.gui;

/** Stub compile-only (26.1+) — marqueur vide, juste assez pour typer un paramètre capturé (jamais lu) par les mixins de {@code apimixin/v26_1/hud/}. Voir {@code Gui.java} pour le pourquoi de ce genre de stub sur 26.1+. */
public abstract class Font {

    /** Largeur d'un texte mis en forme, en pixels de police. */
    public int width(net.minecraft.network.chat.FormattedText text) { return 0; }

    /** {@code Font$DisplayMode} — dessiné normalement, à travers les murs, ou décalé (vérifié javap, 26.3). */
    public enum DisplayMode { NORMAL, SEE_THROUGH, POLYGON_OFFSET }
}
