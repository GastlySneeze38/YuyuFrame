package com.yuyuframe.launcheragent.runtime.ui.config;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Reçoit les réglages déclarés par un module — voir
 * {@code LauncherModule.settings(SettingList)}.
 *
 * <p>Un module écrit :
 * <pre>
 * &#64;Override
 * protected void settings(SettingList s) {
 *     s.slider("fov", "FOV", "Réglages", 30f, 110f, 1f, () -&gt; fovValue, v -&gt; fovValue = v);
 *     s.toggle("smooth", "Transition douce", () -&gt; smooth, v -&gt; smooth = v);
 * }
 * </pre>
 *
 * <p>Chaque méthode renvoie {@code this} pour permettre l'enchaînement, et
 * accepte une surcharge avec description et/ou condition d'activation. La
 * catégorie par défaut est {@code "Général"}, comme l'était celle des
 * annotations remplacées.
 *
 * <p>Les ids en double sont refusés et journalisés : deux réglages partageant
 * un id s'écraseraient mutuellement sur disque, ce qui est exactement le genre
 * de panne silencieuse que ce système remplace.
 */
public final class SettingList {

    public static final String DEFAULT_CATEGORY = "Général";

    private final String moduleId;
    private final List<Setting> settings = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    public SettingList(String moduleId) {
        this.moduleId = moduleId;
    }

    public List<Setting> build() {
        return Collections.unmodifiableList(settings);
    }

    private SettingList add(Setting s) {
        if (!ids.add(s.id)) {
            LauncherLog.err("[SettingList] " + moduleId + " : id de réglage en double \"" + s.id
                + "\" — ignoré (les deux se seraient écrasés dans le fichier de config)");
            return this;
        }
        settings.add(s);
        return this;
    }

    // ── Toggle ───────────────────────────────────────────────────────────────

    public SettingList toggle(String id, String name, BooleanSupplier get, Consumer<Boolean> set) {
        return toggle(id, name, "", DEFAULT_CATEGORY, null, get, set);
    }

    public SettingList toggle(String id, String name, String category, BooleanSupplier get, Consumer<Boolean> set) {
        return toggle(id, name, "", category, null, get, set);
    }

    public SettingList toggle(String id, String name, String description, String category,
                              BooleanSupplier enabledWhen, BooleanSupplier get, Consumer<Boolean> set) {
        return add(new Setting.Toggle(id, name, description, category, enabledWhen, get, set));
    }

    // ── Slider ───────────────────────────────────────────────────────────────

    public SettingList slider(String id, String name, String category, float min, float max, float step,
                              Setting.FloatSupplier get, Setting.FloatConsumer set) {
        return slider(id, name, "", category, min, max, step, null, get, set);
    }

    public SettingList slider(String id, String name, String description, String category,
                              float min, float max, float step, BooleanSupplier enabledWhen,
                              Setting.FloatSupplier get, Setting.FloatConsumer set) {
        return add(new Setting.Slider(id, name, description, category, enabledWhen, min, max, step, get, set));
    }

    // ── Dropdown ─────────────────────────────────────────────────────────────

    public SettingList dropdown(String id, String name, String category, String[] options,
                                IntSupplier get, IntConsumer set) {
        return dropdown(id, name, "", category, options, null, get, set);
    }

    public SettingList dropdown(String id, String name, String description, String category,
                                String[] options, BooleanSupplier enabledWhen,
                                IntSupplier get, IntConsumer set) {
        return add(new Setting.Dropdown(id, name, description, category, enabledWhen,
            Collections.unmodifiableList(Arrays.asList(options)), get, set));
    }

    // ── Color ────────────────────────────────────────────────────────────────

    public SettingList color(String id, String name, String category,
                             Supplier<UiColor> get, Consumer<UiColor> set) {
        return color(id, name, "", category, null, get, set);
    }

    public SettingList color(String id, String name, String description, String category,
                             BooleanSupplier enabledWhen, Supplier<UiColor> get, Consumer<UiColor> set) {
        return add(new Setting.Color(id, name, description, category, enabledWhen, get, set));
    }

    // ── Keybind ──────────────────────────────────────────────────────────────

    /** Ligne compacte « touche + commande + supprimer » — voir {@link Setting.Inline}. */
    public SettingList inlineKeyed(String id, String category,
                                   Supplier<String> keyGet, Consumer<String> keySet,
                                   String namePlaceholder, Supplier<String> nameGet, Consumer<String> nameSet,
                                   String placeholder, Supplier<String> textGet, Consumer<String> textSet,
                                   Runnable delete) {
        return add(new Setting.Inline(id, category, null, keyGet, keySet,
            namePlaceholder, nameGet, nameSet, placeholder, textGet, textSet, delete));
    }

    /** Ligne compacte « libellé figé + champ + oublier » — voir {@link Setting.Inline}. */
    public SettingList inlineLabeled(String id, String category, String fixedLabel, String placeholder,
                                     Supplier<String> textGet, Consumer<String> textSet,
                                     Runnable delete) {
        return inlineLabeled(id, category, fixedLabel, placeholder, textGet, textSet, delete, false);
    }

    /**
     * Variante à champ MASQUÉ (mot de passe), avec un œil pour le révéler —
     * voir {@link Setting.Inline#masked}.
     */
    public SettingList inlineLabeled(String id, String category, String fixedLabel, String placeholder,
                                     Supplier<String> textGet, Consumer<String> textSet,
                                     Runnable delete, boolean masked) {
        Setting.Inline inline = new Setting.Inline(id, category, fixedLabel, null, null,
            null, null, null, placeholder, textGet, textSet, delete);
        if (masked) inline.masked();
        return add(inline);
    }

    /** Champ de saisie d'une ligne. */
    public SettingList text(String id, String name, String description, String category,
                            BooleanSupplier enabledWhen, String placeholder,
                            Supplier<String> get, Consumer<String> set) {
        return add(new Setting.Text(id, name, description, category, enabledWhen, placeholder, get, set, true));
    }

    /** Champ de saisie NON persisté — pour une ligne engendrée depuis une liste, voir {@link Setting#persistent}. */
    public SettingList transientText(String id, String name, String description, String category,
                                     String placeholder, Supplier<String> get, Consumer<String> set) {
        return add(new Setting.Text(id, name, description, category, null, placeholder, get, set, false));
    }

    /** Ligne bouton — une action, aucune valeur. */
    public SettingList action(String id, String name, String description, String category, Runnable run) {
        return add(new Setting.Action(id, name, description, category, null, run));
    }

    /** Donnée persistée que le module édite dans son PROPRE écran — voir {@link Setting.Opaque}. */
    public SettingList opaque(String id, Supplier<String> get, Consumer<String> set) {
        return add(new Setting.Opaque(id, get, set));
    }

    public SettingList keybind(String id, String name, String category,
                               Supplier<String> get, Consumer<String> set) {
        return keybind(id, name, "", category, null, get, set);
    }

    public SettingList keybind(String id, String name, String description, String category,
                               BooleanSupplier enabledWhen, Supplier<String> get, Consumer<String> set) {
        return add(new Setting.Keybind(id, name, description, category, enabledWhen, get, set));
    }
}
