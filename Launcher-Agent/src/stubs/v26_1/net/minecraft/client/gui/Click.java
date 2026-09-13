package net.minecraft.client.gui;

/**
 * Stub compile-only — obfusqué en gzc dans client-mappings-1.21.11
 * (record introduit avec la réécriture Blaze3D, remplace les paramètres
 * (double mouseX, double mouseY, int button) historiques d'Element.mouseClicked
 * sur cette version). Voir ScreenStubPatcher pour le patch bytecode qui
 * remplace ce stub par la vraie classe obfusquée au chargement, et
 * UiScreenBase pour le pourquoi (nouvelle surcharge mouseClicked).
 *
 * Accesseurs vérifiés dans les mappings 1.21.11 : x()/y()/button() — mêmes
 * noms Yarn "named" que déclarés ici, aucun renommage nécessaire pour ceux-là
 * (contrairement à mouseClicked lui-même).
 */
public abstract class Click {
    public double x() { return 0; }
    public double y() { return 0; }
    public int button() { return 0; }
}
