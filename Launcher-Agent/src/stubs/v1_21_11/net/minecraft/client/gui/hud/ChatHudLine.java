package net.minecraft.client.gui.hud;

import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gfc}) — une ligne de chat.
 *
 * <p>La FORME diffère de la 26.1.2, et c'est tout l'intérêt des points d'accès
 * du chat : ici {tick de création, contenu, signature, indicateur} ; là-bas
 * {contenu, source, étiquette}. Aucun renommage ne rapproche ces deux
 * structures — d'où des points d'accès qui nomment une OPÉRATION plutôt que de
 * rendre la ligne.
 *
 * <p>C'est un {@code record} en jeu ; déclaré en classe ici, ce qui suffit :
 * seuls comptent les noms et descripteurs des accesseurs.
 */
public class ChatHudLine {

    private ChatHudLine() {
    }

    public Text content() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Poignée opaque — à repasser telle quelle à {@code ChatHud.addMessage}. */
    public MessageSignatureData signature() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Poignée opaque — idem. */
    public MessageIndicator indicator() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
