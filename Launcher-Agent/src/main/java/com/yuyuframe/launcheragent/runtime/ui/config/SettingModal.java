package com.yuyuframe.launcheragent.runtime.ui.config;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Formulaire MODAL À PLUSIEURS ÉTAPES, demandé par un module et affiché par
 * l'écran de configuration.
 *
 * <h2>Pourquoi ça existe</h2>
 *
 * Créer une macro directement dans la liste marche, mais mal : on ajoute une
 * ligne vide, on clique le bouton de touche, on appuie, on clique le champ, on
 * tape — quatre gestes sans guidage, et une ligne à moitié remplie si on
 * s'arrête en route. L'utilisateur a demandé mieux : « fait un system de modal
 * a plusieurs étapes ça sera bien plus pratique ». Une étape = une question,
 * et l'entrée n'est créée qu'à la fin.
 *
 * <h2>Comment un module la demande</h2>
 *
 * Un module ne référence AUCUNE classe d'écran (voir {@code LauncherModule}) —
 * il pose donc une DEMANDE ici, que l'écran de configuration consomme à la
 * frame suivante. Même idiome que la navigation d'écran
 * ({@code UiScreenBase.hasPendingNavigation}), pour la même raison : garder la
 * couche des modules indépendante de la couche d'interface.
 */
public final class SettingModal {

    /** Une question. {@link #keybind} vrai = capture d'une touche, faux = saisie de texte. */
    public static final class Step {
        public final String title;
        public final String placeholder;
        public final boolean keybind;

        public Step(String title, String placeholder, boolean keybind) {
            this.title = title;
            this.placeholder = placeholder;
            this.keybind = keybind;
        }

        public static Step key(String title) { return new Step(title, null, true); }
        public static Step text(String title, String placeholder) { return new Step(title, placeholder, false); }
    }

    public final String title;
    public final List<Step> steps;
    /** Reçoit une réponse par étape, dans l'ordre — appelé UNIQUEMENT si l'utilisateur va au bout. */
    public final Consumer<List<String>> onComplete;

    public SettingModal(String title, Consumer<List<String>> onComplete, Step... steps) {
        this.title = title;
        this.onComplete = onComplete;
        this.steps = Arrays.asList(steps);
    }

    // ── Demande en attente ────────────────────────────────────────────────
    //
    // Statique et volontairement simple : il n'y a qu'un seul écran de
    // configuration ouvert à la fois, et une seule modale possible dessus.

    private static SettingModal pending;

    /** Demande l'ouverture — l'écran de configuration la prendra à sa prochaine frame. */
    public static void request(SettingModal modal) {
        pending = modal;
    }

    /** {@code null} s'il n'y a rien à ouvrir. Consomme la demande. */
    public static SettingModal consume() {
        SettingModal m = pending;
        pending = null;
        return m;
    }
}
