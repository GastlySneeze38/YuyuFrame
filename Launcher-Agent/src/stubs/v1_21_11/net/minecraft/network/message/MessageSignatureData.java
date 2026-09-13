package net.minecraft.network.message;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code yu}) — poignée OPAQUE : la
 * signature cryptographique d'un message de chat.
 *
 * <p>Aucun membre : elle n'est que TRANSPORTÉE d'une ligne à l'autre lors
 * d'une fusion, jamais lue.
 *
 * <p>Le paquet compte autant que le nom : {@code YarnNamedRemapper} traduit une
 * classe en cherchant son nom Yarn COMPLET dans les mappings. La ranger sous un
 * paquet commode ({@code client.gui.hud}, à côté de son usage) l'aurait laissée
 * introuvable, donc non traduite — et le descripteur d'{@code addMessage}
 * aurait pointé une classe inexistante à l'exécution.
 */
public class MessageSignatureData {

    private MessageSignatureData() {
    }
}
