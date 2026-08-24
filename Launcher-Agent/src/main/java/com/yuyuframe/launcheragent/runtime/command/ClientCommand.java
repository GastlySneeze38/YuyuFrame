package com.yuyuframe.launcheragent.runtime.command;

/**
 * Commande client (préfixe {@code /yf} ou autre, voir ROADMAP-agent.md Phase
 * 4.5) — même principe que {@link com.yuyuframe.launcheragent.runtime.ui.LauncherModule}
 * pour les modules : n'importe quel module peut enregistrer la sienne via
 * {@link ClientCommandRegistry#register}, sans toucher au mixin d'interception
 * ({@code apimixin/v26_1/chat/ChatSendMixin261}).
 *
 * Toutes les commandes sont publiques et documentées (pas de gating dev vs
 * utilisateur, décision actée dans la roadmap) — le vrai garde-fou est la
 * robustesse d'exécution : {@link ClientCommandRegistry#dispatch} isole
 * chaque appel dans son propre try/catch, une commande qui lève ne doit
 * jamais faire planter le hook de chat.
 */
public interface ClientCommand {
    /**
     * Nom complet de la commande, SANS le "/" ni les arguments — un seul mot
     * ("shader-reload") ou plusieurs séparés par un espace pour un
     * sous-commande ("yf safe-mode") — voir {@link ClientCommandRegistry#dispatch}
     * pour la résolution par préfixe le plus long.
     */
    String name();

    /** Résumé court affiché par une future commande d'aide/listing — jamais vide. */
    String description();

    /** {@code args} = les tokens restants après le nom (ex: {"activate"} pour "/yf safe-mode activate"). */
    void execute(String[] args);
}
