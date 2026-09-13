package com.mojang.brigadier.tree;

/**
 * Stub compile-only — un nœud de l'arbre de commandes envoyé par le serveur.
 * Voir {@code MacroModule} : c'est en y cherchant « login » qu'on détecte,
 * sans lire le moindre message, qu'un serveur a un système d'authentification.
 */
public class CommandNode<S> {
    CommandNode() {}
    /** {@code null} si la commande n'est pas déclarée. Descripteur vérifié sur brigadier-1.3.10 : {@code (Ljava/lang/String;)Lcom/mojang/brigadier/tree/CommandNode;}. */
    public CommandNode<S> getChild(String name) { return null; }
    public String getName() { return null; }
    /**
     * Greffe un enfant (fusionne avec un enfant de même nom) — pour
     * {@code AccessPoint.NETWORK_ADD_CLIENT_COMMANDS}. Descripteur vérifié sur
     * brigadier-1.3.10 : {@code (Lcom/mojang/brigadier/tree/CommandNode;)V}.
     */
    public void addChild(CommandNode<S> node) {}
}
