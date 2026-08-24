package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.apimixin.HookPoint;

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
    /** Voir {@link com.yuyuframe.launcheragent.runtime.ui.ModuleGroup#shortDescription} — même principe, pour un module non groupé dont la description serait exceptionnellement trop longue. {@code null} (défaut) pour la quasi-totalité des modules (description déjà courte). */
    public final String shortDescription;

    private boolean enabled;

    /**
     * Marqueur "favori" (demandé explicitement, agencement Grille d'icônes :
     * "la bande du dessous est cliquable pour activer/désactiver le module
     * et le cœur c'est un système de favori") — VOLONTAIREMENT séparé de
     * {@link #enabled} : un mod peut être favori sans être actif (juste
     * épinglé pour le retrouver vite) et inversement. Purement déclaratif
     * ici (pas de tri/filtre par favori pour l'instant, pas demandé) —
     * persisté comme {@link #enabled} (voir HudConfigStore, clé
     * {@code <id>.favorite}), lu/écrit directement par UiMainMenuScreen.
     */
    public boolean favorite;

    /**
     * URL HTTPS d'icône distante pour la carte de ce module (demandé
     * explicitement : "ajoute des icônes pour tous les modules", même
     * système de fetch HTTPS-en-mémoire que la galerie Modrinth — voir
     * {@link com.yuyuframe.launcheragent.apigraphic.UiRemoteImage}).
     * {@code null} (défaut) = pas d'icône dédiée, {@code ModCard} retombe
     * sur la pastille-lettre existante. Mutable et assigné APRÈS le
     * {@code super(...)} (dans le corps du constructeur de chaque module,
     * voir FpsModule etc.) plutôt qu'un nouveau paramètre de constructeur —
     * évite de retoucher la signature des ~40 modules existants pour cette
     * poignée qui en a besoin (même choix que {@link #favorite}).
     */
    public String iconUrl;

    /**
     * Construit une URL icons8 (voir {@link #iconUrl}) à partir du seul nom
     * d'icône — évite de répéter le préfixe dans chaque module. Style
     * "ios-filled", 100px, blanc (cohérent sur toutes les cartes, quel que
     * soit le fond accent/thème derrière — voir ModCard). Noms vérifiés
     * individuellement (HTTP 200, {@code image/png}) avant utilisation ici,
     * voir ModuleRegistry pour la liste complète.
     */
    public static String icons8(String name) {
        return "https://img.icons8.com/ios-filled/100/ffffff/" + name + ".png";
    }

    /**
     * Sentinelle spéciale pour {@link #iconUrl} (voir {@code CrosshairModule}) —
     * PAS une URL HTTPS, demande à {@code UiMainMenuScreen.ModCard} de
     * dessiner un réticule baké localement (voir son {@code crosshairImage})
     * au lieu de faire un fetch distant. Vit ICI (pas dans le screen lui-même)
     * pour qu'un module (CrosshairModule) n'ait besoin de dépendre que de sa
     * propre classe de base, jamais d'un écran concret — même principe
     * d'architecture que le reste de {@link LauncherModule}, voir sa javadoc
     * de classe ("un module n'écrit aucun code d'écran"). Constante définie
     * ici, LUE là-bas.
     */
    public static final String ICON_LOCAL_CROSSHAIR = "local:crosshair";

    /**
     * Points d'accroche déclarés par ce module (ROADMAP-agent.md §3.2) — pure
     * métadonnée légère, jamais consultée par ce module lui-même : sert
     * uniquement d'audit/documentation (quel module dépend de quel {@link
     * HookPoint}). Le tissage réel du mixin apimixin correspondant est
     * gouverné par {@code VanillaHookRegistry.isUsed(point)}, alimenté par
     * l'appel RÉEL à {@code VanillaHookRegistry.register(point, ...)} que ce
     * module doit faire lui-même (typiquement dans son constructeur, juste
     * après {@code super(...)}) — déclarer un HookPoint ici sans
     * l'enregistrer ne suffit PAS à faire weaver son mixin.
     */
    public final HookPoint[] hookPoints;

    protected LauncherModule(String id, String name, String description, boolean enabledByDefault) {
        this(id, name, description, null, enabledByDefault);
    }

    protected LauncherModule(String id, String name, String description, String shortDescription, boolean enabledByDefault) {
        this(id, name, description, shortDescription, enabledByDefault, EMPTY_HOOKPOINTS);
    }

    protected LauncherModule(String id, String name, String description, boolean enabledByDefault, HookPoint... hookPoints) {
        this(id, name, description, null, enabledByDefault, hookPoints);
    }

    protected LauncherModule(String id, String name, String description, String shortDescription, boolean enabledByDefault, HookPoint... hookPoints) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.shortDescription = shortDescription;
        this.enabled = enabledByDefault;
        this.hookPoints = hookPoints != null ? hookPoints : EMPTY_HOOKPOINTS;
    }

    private static final HookPoint[] EMPTY_HOOKPOINTS = new HookPoint[0];

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
    public void onRenderOverlay(com.yuyuframe.launcheragent.apigraphic.UiRenderer renderer, int vpWidth, int vpHeight) {}
}
