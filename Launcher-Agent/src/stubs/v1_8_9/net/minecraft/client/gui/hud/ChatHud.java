package net.minecraft.client.gui.hud;

import net.minecraft.text.Text;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code avt}).
 *
 * <p>{@code messages} (l'historique logique) et {@code visibleMessages} (les
 * lignes découpées) sont PRIVÉS : voir {@code ChatHudAccessor189}.
 * {@code addMessage(Text, int)} et {@code reset()} sont publics ;
 * {@code reset()} reconstruit les lignes visibles depuis {@code messages}.
 */
public class ChatHud {

    private ChatHud() {
    }

    public void addMessage(Text text, int id) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void reset() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
