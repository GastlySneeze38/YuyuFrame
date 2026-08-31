package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.ui.config.Setting;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

import java.util.List;

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
     * {@link com.yuyuframe.launcheragent.apigraphic.core.UiRemoteImage}).
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
     * métadonnée légère, jamais consultée par ce module lui-même. Deux
     * consommateurs (2026-08-25, §12) :
     * <ul>
     *   <li>{@code IsolatedBootstrap.filterConfigByHookPoints()} — lit CE
     *       tableau, via {@code ModuleRegistry.declaredHookPoints()}, pour
     *       décider AVANT le tissage quels mixins {@code apimixin/} garder
     *       dans le JSON envoyé à Mixin. C'est la déclaration qui compte
     *       réellement : un mixin dont le HookPoint n'apparaît dans le
     *       {@code hookPoints} d'AUCUN module (ni dans {@code
     *       ModuleRegistry.INFRA_HOOK_POINTS}) est retiré du JSON avant même
     *       que Mixin ne le charge.</li>
     *   <li>{@code VanillaHookRegistry.auditDeclarations()} — compare ce
     *       tableau à ce qui est RÉELLEMENT enregistré à l'exécution ({@code
     *       VanillaHookRegistry.register(point, ...)}, typiquement dans le
     *       constructeur juste après {@code super(...)}) et journalise tout
     *       écart. Un HookPoint utilisé sans être déclaré ici casserait le
     *       filtrage ci-dessus (mixin retiré à tort) — l'audit le signale en
     *       {@code [ERR]} dès le lancement suivant.</li>
     * </ul>
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

    /**
     * Déclare les réglages exposés par ce module — surchargé par tout module
     * qui en a. Ne fait rien par défaut (module sans réglage).
     *
     * <p>Remplace les annotations {@code @Config*} (supprimées le 2026-08-30) :
     * voir {@link Setting} pour les quatre défauts de l'approche par réflexion,
     * dont le plus coûteux — la clé de persistance était le nom du champ Java,
     * donc un simple renommage effaçait le réglage chez tous les utilisateurs.
     *
     * <p>Appelé UNE SEULE FOIS, paresseusement, au premier appel de
     * {@link #settings()} — jamais depuis le constructeur, où les champs du
     * module ne sont pas encore initialisés.
     */
    protected void settings(SettingList list) {}

    private List<Setting> cachedSettings;

    /**
     * Réglages de ce module, dans l'ordre de déclaration. Jamais {@code null}
     * (liste vide si le module n'en a pas).
     *
     * <p>Construit à la demande puis mémorisé : les lambdas capturent
     * {@code this}, elles restent donc valides pour toute la durée de vie du
     * module, et l'écran de config est reconstruit à chaque ouverture.
     */
    public final List<Setting> settings() {
        if (cachedSettings == null) {
            SettingList list = new SettingList(id);
            settings(list);
            cachedSettings = list.build();
        }
        return cachedSettings;
    }

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

    /**
     * Même rôle que {@link #onRenderOverlay}, mais appelé DANS la passe GUI de
     * vanilla (26.1.2 uniquement, via {@code HudOverlayRenderer.renderInVanillaGui})
     * — donc AVANT la présentation de la frame, avec la cible
     * {@code VanillaGuiTarget} armée. Ne fait rien par défaut.
     *
     * <p>Deux points d'entrée plutôt qu'un seul déplacé : tout ce que fait un
     * module dans {@link #onRenderOverlay} ne sait pas forcément s'émettre en
     * élément de GUI. {@code CrosshairModule}, par exemple, mélange des rects
     * (qui savent) et une icône (qui ne sait pas encore) — le basculer d'office
     * couperait son rendu en deux passes différentes. Chaque module migre donc
     * quand il est prêt, en surchargeant CETTE méthode ; les autres gardent
     * l'ancien chemin après présentation.
     *
     * <p>Un module qui surcharge les deux doit se garder du double-dessin : la
     * passe GUI et l'appel après présentation ont lieu dans la MÊME frame (voir
     * {@code LowHealthTintModule}, qui note qu'il a déjà dessiné).
     */
    public void onRenderInVanillaGui(com.yuyuframe.launcheragent.apigraphic.UiRenderer renderer, int vpWidth, int vpHeight) {}

    /**
     * {@code true} si {@link #onRenderInVanillaGui} doit AUSSI être appelé
     * quand un écran vanilla/mod est ouvert (tchat, inventaire, conteneur…).
     * Défaut {@code false} : l'overlay ne s'affiche qu'en jeu, écran fermé.
     *
     * <p>Existe pour les overlays COLLÉS à un élément du HUD vanilla, qui,
     * lui, continue d'être dessiné derrière les écrans : la barre de faim
     * reste visible tchat ouvert, donc l'indicateur de saturation qui se pose
     * dessus doit l'être aussi (retour utilisateur 2026-08-31 : « il ne
     * s'affiche plus dans le chat, il faut qu'il s'affiche de partout »).
     * Un overlay plein écran comme la teinte de vie basse n'a pas la même
     * évidence — d'où un opt-in par module plutôt qu'un changement global.
     *
     * <p>Ne contourne PAS la touche F1 : quand le joueur masque l'interface
     * vanilla, la barre de faim disparaît, et ce qui se pose dessus avec.
     */
    public boolean renderInVanillaGuiWhenScreenOpen() { return false; }
}
