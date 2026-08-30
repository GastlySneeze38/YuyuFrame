package com.yuyuframe.launcheragent.runtime.ui.config;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Un réglage exposé par un module — <b>déclaré explicitement</b>, jamais
 * découvert par réflexion.
 *
 * <p>Remplace les annotations {@code @ConfigSlider}/{@code @ConfigToggle}/…
 * (supprimées le 2026-08-30). Ce que la réflexion coûtait :
 * <ul>
 *   <li>le même mapping annotation→type écrit TROIS fois (construction de
 *       l'écran, lecture du disque, écriture sur disque) — en ajouter un
 *       quatrième oubliait forcément un des trois ;</li>
 *   <li>aucune vérification par javac : un {@code @ConfigSlider} posé sur un
 *       {@code int} compilait, affichait un curseur et ne faisait rien, sans
 *       le moindre log ;</li>
 *   <li>{@code getDeclaredFields()} ne remontait pas la hiérarchie : un
 *       réglage commun placé sur une classe de base aurait disparu de l'écran
 *       ET du disque ;</li>
 *   <li>surtout, la clé de persistance était le NOM DU CHAMP JAVA — renommer
 *       un champ effaçait silencieusement le réglage de tous les
 *       utilisateurs. D'où {@link #id}, découplé du code.</li>
 * </ul>
 *
 * <p>Les accesseurs sont des lambdas ({@code () -> champ} / {@code v -> champ = v}) :
 * typées, vérifiées à la compilation, et suivies par le renommage de l'IDE.
 *
 * <p>Ordre d'affichage = ordre de déclaration dans
 * {@code LauncherModule.settings(SettingList)}, regroupé par {@link #category}
 * (onglets créés dans l'ordre de première apparition) — même comportement
 * qu'avant, où il dépendait de l'ordre des champs.
 */
public abstract class Setting {

    /**
     * Identifiant STABLE, unique dans le module — sert de clé de persistance
     * ({@code <moduleId>.setting.<id>}). Volontairement distinct du nom du
     * champ Java : renommer le champ ne doit rien casser côté utilisateur.
     */
    public final String id;
    /** Libellé affiché, passé par {@code Lang.tr} au moment du rendu. */
    public final String name;
    public final String description;
    /** Onglet d'accueil de la ligne. */
    public final String category;

    /**
     * Condition d'activation, ou {@code null} si le réglage est toujours
     * actif. Faux = ligne visible mais grisée et non-interactive.
     *
     * <p>Généralise l'ancien couple {@code dependsOnField}/{@code dependsOnValue}
     * qui n'existait que sur les curseurs et ne savait comparer que des
     * {@code int} : n'importe quel réglage peut désormais dépendre de
     * n'importe quelle condition, y compris d'un booléen.
     */
    public final BooleanSupplier enabledWhen;

    protected Setting(String id, String name, String description, String category, BooleanSupplier enabledWhen) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.enabledWhen = enabledWhen;
    }

    /** Valeur courante sous forme de chaîne, pour le fichier de config. */
    public abstract String serialize();

    /**
     * Réapplique une valeur relue du fichier. Doit être tolérant : une valeur
     * corrompue ou d'un ancien format se signale par {@code false}, l'appelant
     * garde alors le défaut du module.
     */
    public abstract boolean deserialize(String raw);

    // ── Types concrets ───────────────────────────────────────────────────────

    public static final class Toggle extends Setting {
        public final BooleanSupplier get;
        public final Consumer<Boolean> set;

        Toggle(String id, String name, String desc, String cat, BooleanSupplier enabledWhen,
               BooleanSupplier get, Consumer<Boolean> set) {
            super(id, name, desc, cat, enabledWhen);
            this.get = get; this.set = set;
        }

        @Override public String serialize() { return String.valueOf(get.getAsBoolean()); }
        @Override public boolean deserialize(String raw) {
            set.accept(Boolean.parseBoolean(raw));
            return true;
        }
    }

    public static final class Slider extends Setting {
        public final float min, max, step;
        public final FloatSupplier get;
        public final FloatConsumer set;

        Slider(String id, String name, String desc, String cat, BooleanSupplier enabledWhen,
               float min, float max, float step, FloatSupplier get, FloatConsumer set) {
            super(id, name, desc, cat, enabledWhen);
            this.min = min; this.max = max; this.step = step;
            this.get = get; this.set = set;
        }

        @Override public String serialize() { return String.valueOf(get.getAsFloat()); }
        @Override public boolean deserialize(String raw) {
            try {
                set.accept(Float.parseFloat(raw.trim()));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    public static final class Dropdown extends Setting {
        /** Libellés des choix, dans l'ordre — l'index sélectionné est la valeur. */
        public final List<String> options;
        public final IntSupplier get;
        public final java.util.function.IntConsumer set;

        Dropdown(String id, String name, String desc, String cat, BooleanSupplier enabledWhen,
                 List<String> options, IntSupplier get, java.util.function.IntConsumer set) {
            super(id, name, desc, cat, enabledWhen);
            this.options = options; this.get = get; this.set = set;
        }

        @Override public String serialize() { return String.valueOf(get.getAsInt()); }
        @Override public boolean deserialize(String raw) {
            try {
                int v = Integer.parseInt(raw.trim());
                // Un index hors bornes viendrait d'un fichier écrit par une
                // version où la liste de choix était plus longue : on refuse
                // plutôt que de laisser un index invalide atteindre l'écran.
                if (v < 0 || v >= options.size()) return false;
                set.accept(v);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    public static final class Color extends Setting {
        public final Supplier<UiColor> get;
        public final Consumer<UiColor> set;

        Color(String id, String name, String desc, String cat, BooleanSupplier enabledWhen,
              Supplier<UiColor> get, Consumer<UiColor> set) {
            super(id, name, desc, cat, enabledWhen);
            this.get = get; this.set = set;
        }

        @Override public String serialize() {
            UiColor c = get.get();
            return c == null ? null : c.r + "," + c.g + "," + c.b + "," + c.a;
        }

        @Override public boolean deserialize(String raw) {
            String[] parts = raw.split(",");
            if (parts.length != 4) return false;
            try {
                set.accept(new UiColor(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                                       Float.parseFloat(parts[2]), Float.parseFloat(parts[3])));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** Touche assignable, stockée par son NOM (ex. {@code "C"}) — voir {@code UiKeybindButton}. */
    public static final class Keybind extends Setting {
        public final Supplier<String> get;
        public final Consumer<String> set;

        Keybind(String id, String name, String desc, String cat, BooleanSupplier enabledWhen,
                Supplier<String> get, Consumer<String> set) {
            super(id, name, desc, cat, enabledWhen);
            this.get = get; this.set = set;
        }

        @Override public String serialize() { return get.get(); }
        @Override public boolean deserialize(String raw) {
            set.accept(raw);
            return true;
        }
    }

    // ── Interfaces fonctionnelles manquantes en Java 8 ───────────────────────
    // L'agent compile en --release 8 : java.util.function fournit
    // Int/Long/Double mais RIEN pour float. Les déclarer ici évite
    // Supplier<Float>/Consumer<Float>, qui boxeraient à chaque frame (les
    // getters sont lus pendant le rendu de l'écran de config).

    @FunctionalInterface
    public interface FloatSupplier { float getAsFloat(); }

    @FunctionalInterface
    public interface FloatConsumer { void accept(float value); }
}
