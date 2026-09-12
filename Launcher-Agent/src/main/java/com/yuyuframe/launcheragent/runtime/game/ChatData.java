package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

/**
 * Accès PARTAGÉ au chat vanilla — <b>zéro réflexion, zéro type du jeu</b>.
 * Troisième façade après {@link ClientData} et {@link PlayerData}.
 *
 * <h2>Pourquoi une façade de plus</h2>
 *
 * {@code ChatEnhancementsModule} lisait la liste de messages du jeu et la
 * modifiait en place : il nommait {@code ChatComponent}, {@code GuiMessage},
 * {@code Component}, {@code GuiMessageSource} et {@code GuiMessageTag} — cinq
 * types 26.1.2, donc un {@code NoClassDefFoundError} garanti ailleurs.
 *
 * <p>La difficulté ici n'était pas les NOMS mais la FORME : une ligne de chat
 * range {contenu, source, étiquette} en 26.1.2 et {tick, contenu, signature,
 * indicateur} en 1.21.11. Aucun renommage ne rapproche ces deux structures. La
 * réponse n'est donc pas de traduire des noms, mais de ne plus exposer la
 * structure du tout : les deux méthodes ci-dessous nomment des OPÉRATIONS, et
 * ce que l'appelant en reçoit est soit du texte, soit une poignée opaque.
 *
 * <h2>Ce qu'est une poignée opaque</h2>
 *
 * Un {@link Object} que l'appelant peut comparer par identité ({@code ==}) et
 * repasser tel quel, jamais transtyper ni interroger. Même contrat que
 * {@code ItemInfo.handle}.
 */
public final class ChatData {
    private ChatData() {}

    /**
     * Message le plus RÉCENT du chat, ou {@code null} si le chat n'est pas
     * encore là ou est vide.
     *
     * @return {@code {ligne, contenu, texteBrut}} — les deux premières cases
     *         sont des poignées opaques, la troisième un {@link String} jamais
     *         nul.
     */
    public static Object[] headMessage() {
        Object v = AccessorRegistry.get(AccessPoint.CHAT_HEAD_MESSAGE, null);
        if (!(v instanceof Object[])) return null;
        Object[] head = (Object[]) v;
        return head.length == 3 && head[2] instanceof String ? head : null;
    }

    /**
     * Fusionne les deux dernières lignes du chat en une seule, suffixée
     * {@code " (xN)"}.
     *
     * @param firstContent poignée de contenu de la PREMIÈRE occurrence, telle
     *                     que rendue par {@link #headMessage()} — et NON celle
     *                     de la ligne déjà fusionnée, qui traîne son propre
     *                     compteur.
     * @param repeatCount  nombre de répétitions à afficher.
     * @return la nouvelle poignée de ligne de tête, ou {@code null} si la
     *         fusion n'a pas eu lieu. L'appelant DOIT mettre à jour sa
     *         dernière ligne traitée avec cette valeur, sinon il retraitera la
     *         ligne qu'il vient lui-même de créer.
     */
    public static Object mergeRepeated(Object firstContent, int repeatCount) {
        if (firstContent == null) return null;
        return AccessorRegistry.invoke(AccessPoint.CHAT_MERGE_REPEATED, null,
            firstContent, Integer.valueOf(repeatCount));
    }
}
