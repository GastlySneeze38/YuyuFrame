package com.yuyuframe.launcheragent.runtime.modules;

/**
 * Base commune de tout module réel (YuyuPvP, HUD Vanilla+...) — équivalent
 * structurel d'un "Mod" OneConfig : un module N'ÉCRIT AUCUN code d'écran,
 * juste ses champs de réglage annotés (voir package config) et sa logique.
 * {@link UiMainMenuScreen}/{@link UiModConfigScreen}
 * (dans runtime.ui.ingameui) et {@link ConfigScreenBuilder} se chargent de
 * TOUTE la présentation à partir d'ici, par réflexion — un futur module n'a
 * donc plus à toucher au moteur graphique.
 *
 * L'activation (voir {@link #setEnabled}) est SÉPARÉE des réglages de config
 * (annotés) : elle correspond au toggle de la carte mod sur l'écran d'accueil,
 * pas à une ligne de la page de config — même convention que OneConfig.
 */
public abstract class LauncherModule {

    public final String id;
    public final String name;
    public final String description;

    private boolean enabled;

    protected LauncherModule(String id, String name, String description, boolean enabledByDefault) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabled = enabledByDefault;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        onEnabledChanged(enabled);
    }

    /** Surchargé par un module qui doit réagir à sa propre activation/désactivation (ex: (dés)enregistrer son élément HUD). Ne fait rien par défaut. */
    protected void onEnabledChanged(boolean enabled) {}

    /** Appelé par {@link ConfigScreenBuilder} après CHAQUE changement d'un champ de config annoté — surchargeable pour réagir à un réglage précis. Ne fait rien par défaut. */
    public void onConfigChanged() {}

    /**
     * Appelé à CHAQUE frame (via {@link ModuleRegistry#tickAll()}), qu'un
     * écran custom soit ouvert ou non, UNIQUEMENT si {@link #isEnabled()} —
     * pour la logique globale qui doit continuer même en jeu (ex: FOV forcé,
     * MumbleLink). Ne fait rien par défaut.
     */
    public void onTick() {}

    /**
     * Appelé à CHAQUE frame (via {@link ModuleRegistry#renderOverlayAll}),
     * UNIQUEMENT quand aucun écran n'est ouvert (même règle que le HUD
     * vanilla) et UNIQUEMENT si {@link #isEnabled()} — pour un rendu
     * d'overlay plein écran (ex: teinte vie basse). Ne fait rien par défaut.
     */
    public void onRenderOverlay(com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer renderer, int vpWidth, int vpHeight) {}
}
