package net.minecraft.client.gui.hud;

import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gjf}) — pendant de
 * {@code ChatComponent} en 26.1.2.
 *
 * <p>Ces deux méthodes sont PUBLIQUES ici (vérifié {@code javap} sur le jar
 * client réel : {@code method_44811} et {@code method_1817}), alors que la
 * 26.1.2 exige un {@code @Invoker} pour son équivalent d'{@code addMessage}.
 * Seul le champ {@code messages} est privé — voir
 * {@code ChatHudAccessor1211}.
 *
 * <p>Renommage à connaître : {@code rescaleChat()} en 26.1.2 s'appelle
 * {@code refresh()} ici.
 */
public class ChatHud {

    private ChatHud() {
    }

    /**
     * Ajoute un message. La signature et l'indicateur sont des poignées
     * opaques, reprises telles quelles de la ligne remplacée — passer
     * {@code null} pour les deux est accepté par le jeu.
     */
    public void addMessage(Text message, MessageSignatureData signature, MessageIndicator indicator) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Reconstruit les lignes visibles (retour à la ligne) — {@code rescaleChat()} en 26.1.2. */
    public void refresh() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
