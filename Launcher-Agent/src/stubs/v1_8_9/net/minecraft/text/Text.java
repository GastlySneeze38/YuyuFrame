package net.minecraft.text;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code eu}) — une INTERFACE sur
 * cette version, mutable : {@code append} modifie le receveur et le rend.
 */
public interface Text {

    Text copy();

    Text append(Text sibling);

    Style getStyle();

    String asUnformattedString();
}
