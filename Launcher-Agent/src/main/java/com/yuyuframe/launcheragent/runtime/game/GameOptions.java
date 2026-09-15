package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

/**
 * Accès PARTAGÉ aux options du jeu — troisième façade de {@code runtime/game/},
 * après {@link ClientData} (état du client) et {@link PlayerData} (joueur/monde).
 *
 * <p>Créée le 2026-09-09 en migrant les accessors vers {@link AccessorRegistry}.
 * Constat, exactement celui qui avait justifié les deux autres façades :
 * {@code FovModule}, {@code ZoomModule}, {@code FullbrightModule} et
 * {@code HudOverlayRenderer} refaisaient chacun la même descente
 * {@code Options → OptionInstance → champ value}, et TROIS d'entre eux
 * portaient leur propre copie de la règle de réencapsulation ci-dessous.
 *
 * <h2>Poignées opaques</h2>
 *
 * {@link #fovHandle()} &amp; co renvoient un {@code Object} volontairement :
 * l'appelant n'a jamais besoin de savoir que c'est un {@code OptionInstance},
 * il le repasse tel quel à {@link #value} / {@link #setValue}. C'est ce qui
 * permet à une autre version de représenter une option autrement sans toucher
 * un seul module.
 *
 * <h2>Pourquoi on écrit le champ, pas {@code setValue()}</h2>
 *
 * Le champ {@code value} d'{@code OptionInstance} est écrit DIRECTEMENT, jamais
 * via la méthode {@code setValue()} de vanilla : celle-ci déclenche la
 * validation, qui clampe fov et sensibilité — donc annule précisément ce que
 * font le zoom et le module FOV.
 */
public final class GameOptions {

    private GameOptions() {}

    /** Poignée de l'option « champ de vision », ou {@code null}. */
    public static Object fovHandle() {
        return AccessorRegistry.get(AccessPoint.OPTIONS_FOV, null);
    }

    /** Poignée de l'option « sensibilité souris », ou {@code null}. */
    public static Object sensitivityHandle() {
        return AccessorRegistry.get(AccessPoint.OPTIONS_SENSITIVITY, null);
    }

    /** Poignée de l'option « luminosité », ou {@code null}. */
    public static Object gammaHandle() {
        return AccessorRegistry.get(AccessPoint.OPTIONS_GAMMA, null);
    }

    /**
     * Les cinq raccourcis de déplacement, en poignées opaques — avancer,
     * gauche, reculer, droite, sauter — ou {@code null} si l'accès n'est pas
     * disponible.
     *
     * <p>{@code null} plutôt qu'un tableau vide : l'appelant dessine un clavier,
     * et cinq touches manquantes ne se dessinent pas comme cinq touches
     * relâchées. À repasser tel quel à {@link #keyDown} et {@link #keyCode}.
     */
    public static Object[] movementKeys() {
        Object v = AccessorRegistry.get(AccessPoint.OPTIONS_MOVEMENT_KEYS, null);
        return v instanceof Object[] && ((Object[]) v).length == 5 ? (Object[]) v : null;
    }

    /**
     * Les quatre raccourcis d'action, en poignées opaques — attaquer, utiliser,
     * s'accroupir, sprinter (index {@code KEY_*}) — ou {@code null} si l'accès
     * n'est pas disponible. État RÉEL des touches, voir
     * {@link AccessPoint#OPTIONS_ACTION_KEYS}.
     */
    public static Object[] actionKeys() {
        Object v = AccessorRegistry.get(AccessPoint.OPTIONS_ACTION_KEYS, null);
        return v instanceof Object[] && ((Object[]) v).length == 4 ? (Object[]) v : null;
    }

    public static final int KEY_ATTACK = 0;
    public static final int KEY_USE = 1;
    public static final int KEY_SNEAK = 2;
    public static final int KEY_SPRINT = 3;

    /** Ce raccourci est-il enfoncé ? {@code false} si la poignée est absente. */
    public static boolean keyDown(Object handle) {
        if (handle == null) return false;
        return AccessorRegistry.getBoolean(AccessPoint.KEYBIND_IS_DOWN, handle, false);
    }

    /**
     * Code GLFW de la touche liée à ce raccourci, ou {@code -1} si inconnu —
     * l'appelant DOIT distinguer ce {@code -1} d'un vrai code.
     */
    public static int keyCode(Object handle) {
        if (handle == null) return -1;
        return AccessorRegistry.getInt(AccessPoint.KEYBIND_KEY_CODE, handle, -1);
    }

    /**
     * Point de vue courant — {@code "first_person"},
     * {@code "third_person_back"}, {@code "third_person_front"}, ou
     * {@code null} si l'accès n'est pas disponible.
     *
     * <p>{@code null} et non {@code "first_person"} : un appelant qui SAUVEGARDE
     * puis restaure (le freelook) doit pouvoir distinguer « je n'ai rien lu » de
     * « le joueur était en vue subjective », sans quoi il forcerait la vue
     * subjective en sortant.
     */
    public static String perspective() {
        Object v = AccessorRegistry.get(AccessPoint.OPTIONS_PERSPECTIVE, null);
        return v instanceof String ? (String) v : null;
    }

    /** Écrit le point de vue — {@code true} si l'écriture a eu lieu. Un nom inconnu est ignoré. */
    public static boolean setPerspective(String perspective) {
        if (perspective == null) return false;
        Object v = AccessorRegistry.invoke(AccessPoint.OPTIONS_PERSPECTIVE_SET, null, perspective);
        return v instanceof Boolean && (Boolean) v;
    }

    /** HUD masqué par le joueur (F1) — {@code false} si l'accès n'est pas disponible. */
    public static boolean hideGui() {
        return AccessorRegistry.getBoolean(AccessPoint.OPTIONS_HIDE_GUI, null, false);
    }

    /** Valeur courante d'une poignée, ou {@code fallback} si elle est absente/illisible. */
    public static double value(Object handle, double fallback) {
        if (handle == null) return fallback;
        return AccessorRegistry.getDouble(AccessPoint.OPTION_VALUE, handle, fallback);
    }

    /**
     * Écrit une valeur en la RÉENCAPSULANT dans le type de la valeur courante.
     *
     * <p>Règle vérifiée par javap sur 26.1.2 et jusqu'ici recopiée dans trois
     * modules : {@code fov} est un {@code OptionInstance<Integer>},
     * {@code sensitivity} et {@code gamma} des {@code OptionInstance<Double>}.
     * Y écrire le mauvais type COMPILE (le champ est vu comme {@code Object}
     * côté accessor) mais plante au premier déballage côté vanilla — d'où la
     * réencapsulation d'après la valeur lue, plutôt qu'un type choisi par
     * l'appelant.
     *
     * <p>Sans valeur courante lisible, on n'écrit RIEN : deviner le type
     * reviendrait à choisir entre planter vanilla et ne rien faire, et ne rien
     * faire est le seul des deux qui soit récupérable.
     */
    public static void setValue(Object handle, double value) {
        if (handle == null) return;
        Object current = AccessorRegistry.get(AccessPoint.OPTION_VALUE, handle);
        Object boxed = boxLike(current, value);
        if (boxed == null) return;
        AccessorRegistry.invoke(AccessPoint.OPTION_VALUE_SET, handle, boxed);
    }

    /** {@code null} si le type courant est inconnu — voir {@link #setValue}. */
    private static Object boxLike(Object current, double value) {
        if (current instanceof Integer) return Integer.valueOf((int) Math.round(value));
        if (current instanceof Double) return Double.valueOf(value);
        if (current instanceof Float) return Float.valueOf((float) value);
        if (current instanceof Long) return Long.valueOf(Math.round(value));
        return null;
    }
}
