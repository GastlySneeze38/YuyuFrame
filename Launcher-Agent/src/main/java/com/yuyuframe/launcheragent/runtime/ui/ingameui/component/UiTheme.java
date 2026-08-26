package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;

/**
 * Palette centralisée style OneConfig (fond sombre, accent violet) —
 * cohérence visuelle entre tous les widgets/écrans custom.
 *
 * Tous les tokens de couleur (+ RADIUS_MD) sont NON-final —
 * {@link com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings} réassigne
 * déjà certains d'entre eux en direct (pas de couche d'indirection/getter)
 * depuis l'écran "Paramètres" : un réglage "ultra générique" doit changer
 * TOUT l'affichage, pas juste sa propre page. Même mécanisme exploité par
 * {@link #applyMode} pour la bascule dark/light (voir sa javadoc).
 */
public final class UiTheme {
    private UiTheme() {}

    /**
     * Capacité moteur ajoutée (voir audit runtime/ui/ : "dark / light theme"
     * absent — une seule palette câblée en dur). DARK reprend exactement les
     * valeurs d'origine (aucun changement de comportement tant que
     * {@link #applyMode} n'est jamais appelé) ; LIGHT est un jeu de tokens
     * miroir, pensé pour garder le MÊME contraste relatif texte/fond et la
     * même teinte d'accent plutôt qu'une simple inversion naïve des
     * luminances. Non branché à un écran de réglages pour l'instant (voir
     * demande : "dimensionner le moteur", pas le câblage complet) —
     * {@link #applyMode} existe et fonctionne, prêt à être appelé depuis
     * GlobalUiSettings quand ce chantier reprendra.
     */
    public enum Mode { DARK, LIGHT }

    private static Mode currentMode = Mode.DARK;
    public static Mode currentMode() { return currentMode; }

    public static UiColor OVERLAY_BG     = new UiColor(8, 8, 12, 235);
    public static UiColor SIDEBAR_BG     = new UiColor(17, 17, 22, 255);
    public static UiColor SIDEBAR_HOVER  = new UiColor(255, 255, 255, 26);
    public static UiColor SIDEBAR_ACTIVE = new UiColor(139, 124, 255, 34);
    public static UiColor PANEL_BG       = new UiColor(27, 27, 34, 255);
    public static UiColor PANEL_BG_ALT   = new UiColor(34, 34, 43, 255);
    public static UiColor CARD_BG        = new UiColor(31, 31, 39, 255);
    public static UiColor CARD_HOVER     = new UiColor(40, 40, 50, 255);

    public static UiColor ACCENT     = new UiColor(139, 124, 255, 255);
    public static UiColor ACCENT_DIM = new UiColor(139, 124, 255, 70);
    public static UiColor DANGER     = new UiColor(230, 95, 95, 255);

    public static UiColor TEXT_PRIMARY   = new UiColor(232, 232, 238, 255);
    public static UiColor TEXT_SECONDARY = new UiColor(150, 150, 163, 255);
    public static UiColor TEXT_MUTED     = new UiColor(105, 105, 118, 255);

    public static UiColor TRACK_OFF = new UiColor(55, 55, 65, 255);

    /**
     * Palette LIGHT — fond quasi-blanc légèrement teinté violet (pas un gris
     * pur, cohérent avec {@link #ACCENT}), même hiérarchie de contraste que
     * DARK (texte primaire proche du noir, secondaire/muted qui s'éclaircissent
     * progressivement) plutôt qu'une inversion mécanique des valeurs DARK.
     */
    private static final class Light {
        static final UiColor OVERLAY_BG     = new UiColor(250, 250, 252, 235);
        static final UiColor SIDEBAR_BG     = new UiColor(242, 241, 247, 255);
        static final UiColor SIDEBAR_HOVER  = new UiColor(20, 18, 30, 18);
        static final UiColor SIDEBAR_ACTIVE = new UiColor(139, 124, 255, 40);
        static final UiColor PANEL_BG       = new UiColor(255, 255, 255, 255);
        static final UiColor PANEL_BG_ALT   = new UiColor(246, 245, 250, 255);
        static final UiColor CARD_BG        = new UiColor(255, 255, 255, 255);
        static final UiColor CARD_HOVER     = new UiColor(238, 236, 246, 255);

        static final UiColor ACCENT     = new UiColor(112, 94, 240, 255);
        static final UiColor ACCENT_DIM = new UiColor(112, 94, 240, 55);
        static final UiColor DANGER     = new UiColor(205, 60, 60, 255);

        static final UiColor TEXT_PRIMARY   = new UiColor(24, 22, 32, 255);
        static final UiColor TEXT_SECONDARY = new UiColor(90, 87, 102, 255);
        static final UiColor TEXT_MUTED     = new UiColor(140, 137, 150, 255);

        static final UiColor TRACK_OFF = new UiColor(214, 212, 222, 255);
    }

    private static final class Dark {
        static final UiColor OVERLAY_BG     = new UiColor(8, 8, 12, 235);
        static final UiColor SIDEBAR_BG     = new UiColor(17, 17, 22, 255);
        static final UiColor SIDEBAR_HOVER  = new UiColor(255, 255, 255, 26);
        static final UiColor SIDEBAR_ACTIVE = new UiColor(139, 124, 255, 34);
        static final UiColor PANEL_BG       = new UiColor(27, 27, 34, 255);
        static final UiColor PANEL_BG_ALT   = new UiColor(34, 34, 43, 255);
        static final UiColor CARD_BG        = new UiColor(31, 31, 39, 255);
        static final UiColor CARD_HOVER     = new UiColor(40, 40, 50, 255);

        static final UiColor ACCENT     = new UiColor(139, 124, 255, 255);
        static final UiColor ACCENT_DIM = new UiColor(139, 124, 255, 70);
        static final UiColor DANGER     = new UiColor(230, 95, 95, 255);

        static final UiColor TEXT_PRIMARY   = new UiColor(232, 232, 238, 255);
        static final UiColor TEXT_SECONDARY = new UiColor(150, 150, 163, 255);
        static final UiColor TEXT_MUTED     = new UiColor(105, 105, 118, 255);

        static final UiColor TRACK_OFF = new UiColor(55, 55, 65, 255);
    }

    /**
     * Réassigne TOUS les tokens de couleur d'un coup — même philosophie que
     * la réassignation directe déjà en place pour ACCENT/CARD_BG/etc. depuis
     * GlobalUiSettings (voir javadoc de classe) : pas de couche
     * d'indirection, chaque composant qui lit {@code UiTheme.PANEL_BG} au
     * moment de dessiner voit la nouvelle valeur dès la frame suivante, sans
     * qu'aucun composant n'ait besoin d'être modifié pour ça.
     */
    public static void applyMode(Mode mode) {
        currentMode = mode;
        if (mode == Mode.LIGHT) {
            OVERLAY_BG = Light.OVERLAY_BG; SIDEBAR_BG = Light.SIDEBAR_BG; SIDEBAR_HOVER = Light.SIDEBAR_HOVER;
            SIDEBAR_ACTIVE = Light.SIDEBAR_ACTIVE; PANEL_BG = Light.PANEL_BG; PANEL_BG_ALT = Light.PANEL_BG_ALT;
            CARD_BG = Light.CARD_BG; CARD_HOVER = Light.CARD_HOVER;
            ACCENT = Light.ACCENT; ACCENT_DIM = Light.ACCENT_DIM; DANGER = Light.DANGER;
            TEXT_PRIMARY = Light.TEXT_PRIMARY; TEXT_SECONDARY = Light.TEXT_SECONDARY; TEXT_MUTED = Light.TEXT_MUTED;
            TRACK_OFF = Light.TRACK_OFF;
        } else {
            OVERLAY_BG = Dark.OVERLAY_BG; SIDEBAR_BG = Dark.SIDEBAR_BG; SIDEBAR_HOVER = Dark.SIDEBAR_HOVER;
            SIDEBAR_ACTIVE = Dark.SIDEBAR_ACTIVE; PANEL_BG = Dark.PANEL_BG; PANEL_BG_ALT = Dark.PANEL_BG_ALT;
            CARD_BG = Dark.CARD_BG; CARD_HOVER = Dark.CARD_HOVER;
            ACCENT = Dark.ACCENT; ACCENT_DIM = Dark.ACCENT_DIM; DANGER = Dark.DANGER;
            TEXT_PRIMARY = Dark.TEXT_PRIMARY; TEXT_SECONDARY = Dark.TEXT_SECONDARY; TEXT_MUTED = Dark.TEXT_MUTED;
            TRACK_OFF = Dark.TRACK_OFF;
        }
    }

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
