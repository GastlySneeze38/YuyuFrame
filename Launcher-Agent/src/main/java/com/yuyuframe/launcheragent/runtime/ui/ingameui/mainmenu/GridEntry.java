package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    boolean hasToggle() { return enabled != null; }

    public boolean isFavorite() { return favorite.getAsBoolean(); }

    public String name() { return name; }
}
