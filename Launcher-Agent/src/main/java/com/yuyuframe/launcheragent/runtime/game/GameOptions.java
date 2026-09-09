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
