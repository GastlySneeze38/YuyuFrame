package net.minecraft.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.components.ChatComponent;

/** Stub compile-only (26.1+) — marqueur pour typer les paramètres capturés par les mixins de {@code apimixin/v26_1/hud/}. Voir {@code GuiGraphicsExtractor.java} pour le pourquoi de ce genre de stub sur 26.1+. {@code extractCrosshair} ajouté pour l'appel direct depuis {@code HudExtractCrosshairMixin261} (@Redirect, jamais réellement invoqué ici — résolu par la hiérarchie réelle Mojang au runtime). {@code getChat()} PUBLIC (vérifié javap) — ajouté pour {@code ChatEnhancementsModule}. */
public abstract class Gui {
    public void extractCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {}
    public ChatComponent getChat() { return null; }
}
