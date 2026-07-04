package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;

/**
 * Palette centralisée style OneConfig (fond sombre, accent violet) —
 * cohérence visuelle entre tous les widgets/écrans custom.
 *
 * CARD_BG/CARD_HOVER/ACCENT/ACCENT_DIM/SIDEBAR_ACTIVE/RADIUS_MD sont
 * NON-final — {@link com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings}
 * les réassigne en direct (pas de couche d'indirection/getter) depuis l'écran
 * "Paramètres" : un réglage "ultra générique" doit changer TOUT l'affichage,
 * pas juste sa propre page.
 */
public final class UiTheme {
    private UiTheme() {}

    public static final UiColor OVERLAY_BG     = new UiColor(8, 8, 12, 235);
    public static final UiColor SIDEBAR_BG     = new UiColor(17, 17, 22, 255);
    public static final UiColor SIDEBAR_HOVER  = new UiColor(255, 255, 255, 26);
    public static UiColor SIDEBAR_ACTIVE       = new UiColor(139, 124, 255, 34);
    public static final UiColor PANEL_BG       = new UiColor(27, 27, 34, 255);
    public static final UiColor PANEL_BG_ALT   = new UiColor(34, 34, 43, 255);
    public static UiColor CARD_BG              = new UiColor(31, 31, 39, 255);
    public static UiColor CARD_HOVER           = new UiColor(40, 40, 50, 255);

    public static UiColor ACCENT            = new UiColor(139, 124, 255, 255);
    public static UiColor ACCENT_DIM        = new UiColor(139, 124, 255, 70);
    public static final UiColor DANGER      = new UiColor(230, 95, 95, 255);

    public static final UiColor TEXT_PRIMARY   = new UiColor(232, 232, 238, 255);
    public static final UiColor TEXT_SECONDARY = new UiColor(150, 150, 163, 255);
    public static final UiColor TEXT_MUTED      = new UiColor(105, 105, 118, 255);

    public static final UiColor TRACK_OFF = new UiColor(55, 55, 65, 255);

    public static final float RADIUS_SM = 4f;
    public static float RADIUS_MD = 8f;
    public static final float RADIUS_LG = 12f;

    /**
     * Échelle générale de l'interface (Petite/Normale/Grande, voir
     * GlobalUiSettings) — 1f = taille d'origine. Appliquée par les écrans/
     * widgets à leurs propres constantes de mise en page via {@link #scaled}
     * plutôt que par un vrai système de transform/DPI (qui n'existe pas dans
     * ce pipeline de rendu pixel-à-pixel) : chaque constante de taille/
     * espacement doit être multipliée EXPLICITEMENT à son point d'usage.
     */
    public static float UI_SCALE = 1f;

    /** Raccourci {@code v * UI_SCALE} — lisibilité aux points d'usage (voir UiMainMenuScreen/ConfigScreenBuilder). */
    public static float scaled(float v) { return v * UI_SCALE; }

    /**
     * Variante plus claire/plus sombre de {@link #ACCENT} — recalculée à
     * CHAQUE appel depuis la valeur COURANTE de {@code ACCENT} (jamais un
     * champ figé) : {@code ACCENT} est réassignable en direct depuis l'écran
     * "Paramètres" (voir javadoc de classe), un champ dérivé figé au
     * chargement resterait sur l'ancienne couleur après un tel changement.
     * Utilisées pour les dégradés/jeux de lumière (voir UiToggle, ModCard,
     * SidebarItem) — jamais pour une couleur "à plat", qui doit rester ACCENT.
     */
    public static UiColor accentLight() { return UiColor.lerp(ACCENT, new UiColor(255, 255, 255, 255), 0.28f); }
    public static UiColor accentDark() { return UiColor.lerp(ACCENT, new UiColor(0, 0, 0, 255), 0.35f); }
}
