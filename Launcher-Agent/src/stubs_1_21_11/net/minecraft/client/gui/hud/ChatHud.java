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
 * <h2>PIÈGE DE NOMMAGE — lire avant de toucher à {@link #reset()}</h2>
 *
 * L'équivalent de {@code rescaleChat()} (26.1.2) s'appelle ici
 * {@code reset()} ({@code method_1817}, PUBLIQUE) : elle remet le défilement à
 * zéro puis reconstruit les lignes affichées. Yarn a bien une
 * {@code refresh()}, mais c'est {@code method_44813}, **PRIVÉE** — elle ne
 * fait que la reconstruction.
 *
 * <p>Le nom trompe dans les deux sens, et l'erreur ne se voit pas : appeler
 * {@code refresh()} compile, se traduit correctement, et lève un
 * {@code IllegalAccessError} à l'exécution — attrapé par
 * {@code AccessorRegistry}, donc la fusion des messages répétés échouait à
 * mi-chemin (ligne ajoutée, anciennes jamais retirées de l'affichage), ce qui
 * relançait la fusion à chaque passe : compteurs jusqu'à x5 pour deux envois,
 * et auto-ping en prime (v1095, corrigé v1099).
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

    /**
     * Remet le défilement à zéro et reconstruit les lignes affichées —
     * {@code rescaleChat()} en 26.1.2. Voir le PIÈGE DE NOMMAGE ci-dessus :
     * ce n'est PAS {@code refresh()}, qui est privée.
     */
    public void reset() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
