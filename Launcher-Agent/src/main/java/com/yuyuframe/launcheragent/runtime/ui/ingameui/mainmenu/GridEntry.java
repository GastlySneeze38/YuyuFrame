package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Une carte de la grille, quelle que soit sa nature : un module, un groupe
 * de modules, ou une carte « action » (Modrinth). La grille ne connaît que
 * ça ; c'est l'écran qui décide ce que fait chaque carte.
 */
public final class GridEntry {
    /** Identifiant stable (« m:id », « g:id », « a:id ») — sert à suivre une carte d'une reconstruction à l'autre. */
    final String id;
    final String name, description;
    /** Description courte pour la taille d'interface « Grande », {@code null} = pas de version dédiée. */
    final String shortDescription;
    final String iconUrl;
    /** Le clic sur la carte. */
    final Runnable open;
    /** Activé ? {@code null} = pas d'interrupteur (groupe, action). */
    final BooleanSupplier enabled;
    final Consumer<Boolean> setEnabled;
    final BooleanSupplier favorite;
    final Consumer<Boolean> setFavorite;

    public GridEntry(String id, String name, String description, String shortDescription, String iconUrl, Runnable open,
              BooleanSupplier enabled, Consumer<Boolean> setEnabled,
              BooleanSupplier favorite, Consumer<Boolean> setFavorite) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.shortDescription = shortDescription;
        this.iconUrl = iconUrl;
        this.open = open;
        this.enabled = enabled;
        this.setEnabled = setEnabled;
        this.favorite = favorite;
        this.setFavorite = setFavorite;
    }

    /** Un groupe : l'état de chacun de ses modules, pour « 3 sur 5 actifs ». {@code null} ailleurs. */
    Supplier<boolean[]> members;
    /** Le clic sur la carte ouvre des réglages (et non une bascule) : la carte le dit au survol. */
    boolean opensSettings;

    /** Voir {@link #members}. */
    public GridEntry members(Supplier<boolean[]> states) {
        this.members = states;
        return this;
    }

    /** Voir {@link #opensSettings}. */
    public GridEntry opensSettings(boolean value) {
        this.opensSettings = value;
        return this;
    }

    boolean hasToggle() { return enabled != null; }

    boolean isGroup() { return members != null; }

    /** Activé, pour les filtres de l'écran ; un groupe compte s'il a au moins un module actif. */
    public boolean isActive() {
        if (enabled != null) return enabled.getAsBoolean();
        if (members != null) for (boolean b : members.get()) if (b) return true;
        return false;
    }

    /** Une carte qui s'allume (module) : les seules que comptent les filtres Activés / Désactivés. */
    public boolean isToggleable() { return enabled != null; }

    public boolean isFavorite() { return favorite.getAsBoolean(); }

    public String name() { return name; }
}
