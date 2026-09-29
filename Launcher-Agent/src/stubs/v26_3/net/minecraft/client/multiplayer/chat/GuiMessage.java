package net.minecraft.client.multiplayer.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

/**
 * Stub compile-only (26.1+) — {@code record} côté vrai jar, accesseurs
 * PUBLICS générés automatiquement (vérifiés javap) — voir {@code
 * ChatEnhancementsModule}. Classe simple ici (pas un {@code record}) :
 * jamais instanciée par notre code, seulement REÇUE depuis {@code
 * ChatComponent.allMessages} puis interrogée via ces accesseurs.
 */
public abstract class GuiMessage {
    public abstract int addedTime();
    public abstract Component content();
    public abstract MessageSignature signature();
    public abstract GuiMessageSource source();
    public abstract GuiMessageTag tag();
}
