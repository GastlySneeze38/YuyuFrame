package net.minecraft.client.gui.hud;

/**
 * Stub compile-only 1.21.11, nom Yarn — poignée OPAQUE : le HUD vanilla.
 *
 * <p>Un seul membre, {@link #getChatHud()} : pour le reste, ce type ne sert
 * qu'à donner au bytecode le bon descripteur
 * (un champ ou un retour de méthode se résout par son TYPE autant que par son
 * nom). Les liaisons le repassent tel quel en {@code Object} — voir
 * {@code AccessorBindings1211} et les règles de l'unité dans
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 */
public class InGameHud {

    private InGameHud() {
    }

    /** Le chat vanilla — {@code Gui.getChat()} en 26.1.2. */
    public ChatHud getChatHud() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
