package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudPanelRenderer;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.apigraphic.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

/**
 * Réglages GLOBAUX de l'interface — équivalent des préférences OneConfig
 * elles-mêmes (PAS un mod de contenu). Volontairement JAMAIS enregistré dans
 * {@link ModuleRegistry} (jamais dans la grille de mods) : ouvert directement
 * depuis la sidebar ("Paramètres") via UiModConfigScreen construit avec CETTE
 * instance — voir UiMainMenuScreen.
 *
 * Applique ses valeurs directement sur {@link UiTheme} (champs rendus
 * mutables pour ça) et {@link UiInputPoller#menuKeyName} — un effet réel
 * immédiat sur TOUTE l'interface, pas juste sa propre page : demandé
 * explicitement ("ultra générique", "plus juste une option décorative").
 */
public final class GlobalUiSettings extends LauncherModule {

    // Couleur de base d'origine (littérale, PAS l'état courant de UiTheme,
    // potentiellement déjà muté) — pour le HUD uniquement (CARD_BG/CARD_HOVER
    // n'en ont plus besoin, voir onConfigChanged : ils repartent désormais de
    // la palette posée par UiTheme.applyMode, qui gère déjà dark/light).
    // DOIT rester déclaré AVANT INSTANCE ci-dessous : les initialiseurs
    // statiques s'exécutent dans l'ordre TEXTUEL, et le constructeur de
    // INSTANCE appelle onConfigChanged() (qui lit ce champ) — l'avoir après
    // aurait laissé BASE_HUD_BG à null au moment de cet appel
    // (NullPointerException → ExceptionInInitializerError, observé en jeu :
    // le clic sur "Parametres" ne faisait plus jamais rien).
    private static final UiColor BASE_HUD_BG = new UiColor(10, 10, 14, 120);

    public static final GlobalUiSettings INSTANCE = new GlobalUiSettings();

    // Câblage du toggle dark/light ajouté (voir audit runtime/ui/ :
    // UiTheme.applyMode existait déjà comme capacité moteur mais n'était
    // appelé depuis aucun écran) — appliqué en PREMIER dans onConfigChanged()
    // ci-dessous : applyMode réassigne TOUS les tokens de couleur d'un coup,
    // les réglages individuels (accent, opacités...) doivent donc s'appliquer
    // APRÈS, sinon ils seraient écrasés par la palette de base à chaque
    // changement.
    @ConfigDropdown(name = "Thème", description = "Palette générale de l'interface — sombre (par défaut) ou claire.",
        category = "Apparence", options = { "Sombre", "Clair" })
    public int themeMode = 0;

    @ConfigSlider(name = "Opacité du HUD (en jeu)", description = "Transparence des panneaux HUD affichés en jeu (FPS, ping, coordonnées...) — pas les cartes du menu.",
        category = "Apparence", min = 10f, max = 100f, step = 1f)
    public float hudOpacity = BASE_HUD_BG.a * 100f;

    // BUG TROUVÉ (retour utilisateur : "c'est le rayon des coins des HUD et
    // pas du menu principal") — pilotait UiTheme.RADIUS_MD, qui n'est
    // utilisé QUE par les cartes/panneaux du MENU (config, mods...) : les
    // panneaux HUD, eux, ont TOUJOURS leur propre constante dédiée
    // (HudPanelRenderer.RADIUS, jamais alignée sur RADIUS_MD par choix
    // délibéré — voir sa javadoc), donc ce réglage n'avait en réalité AUCUN
    // effet sur le HUD. Pilote désormais HudPanelRenderer.RADIUS, plus
    // UiTheme.RADIUS_MD (qui reste fixe à sa valeur par défaut pour le menu).
    @ConfigSlider(name = "Rayon des coins (HUD)", description = "Arrondi des coins des panneaux HUD affichés en jeu — pas les cartes du menu.",
        category = "Apparence", min = 0f, max = 16f, step = 1f)
    public float cornerRadius = HudPanelRenderer.RADIUS;

    @ConfigColor(name = "Couleur d'accent", description = "Couleur principale utilisée dans tout le menu.", category = "Apparence")
    public UiColor accentColor = UiTheme.ACCENT;

    @ConfigDropdown(name = "Taille de l'interface", description = "Échelle générale du menu (cartes, texte, boutons, réglages...) — pas la taille des éléments HUD affichés en jeu, qui se règlent individuellement (voir chaque module).",
        category = "Apparence", options = { "Petite", "Normale", "Grande" })
    public int uiSize = 1;

    // Demandé explicitement ("plusieurs agencements comme dans Lunar, choix
    // via une icône à côté de la barre de recherche") — le champ existe ICI
    // (persisté comme tout autre réglage @ConfigDropdown, voir HudConfigStore)
    // uniquement pour la sauvegarde disque ; le changement rapide se fait
    // depuis UiMainMenuScreen (icône dédiée), pas depuis cet écran Paramètres,
    // mais rien n'empêche de le faire ici aussi (même champ, même source de
    // vérité). Lu directement par UiMainMenuScreen.rebuildAll() à chaque
    // reconstruction de la grille.
    @ConfigDropdown(name = "Affichage des cartes (menu)", description = "Disposition des cartes de mods dans le menu principal.",
        category = "Apparence", options = { "Détaillé", "Compacte", "Grille d'icônes" })
    public int cardLayout = 2; // Grille d'icônes par défaut — retour utilisateur : "la grille est parfaite, mets-la par défaut".

    // Demandé explicitement ("améliore le système de favori... rajoute un
    // settings pour mettre à l'écart les modules en favori") — les favoris
    // remontent TOUJOURS en tête de grille (voir UiMainMenuScreen.rebuildAll(),
    // comportement inconditionnel, PAS piloté par ce réglage) ; celui-ci ne
    // pilote QUE l'affichage d'une section séparée avec titres ("FAVORIS" /
    // "AUTRES MODULES") au lieu d'une simple grille continue.
    @ConfigToggle(name = "Séparer les favoris", description = "Affiche les mods favoris dans une section dédiée, avec un titre, en haut de la grille du menu principal.",
        category = "Apparence")
    public boolean separateFavorites = true; // Activé par défaut — retour utilisateur explicite.

    @ConfigKeybind(name = "Touche du menu", description = "Touche qui ouvre/ferme le menu YuyuFrame en jeu.", category = "Général")
    public String menuKey = UiInputPoller.menuKeyName;

    // Choix de langue (demande explicite : "ajoute le fait que on puisse
    // choisir notre langue et donc avoir plusieurs fichier de traduction") —
    // voir Lang pour le mécanisme (texte source français utilisé comme clé
    // de recherche dans lang/<id>.json, jamais de clé abstraite dédiée à
    // maintenir dans chaque module). Les index de {@link Lang#LANGUAGE_IDS}/
    // {@link Lang#LANGUAGE_NAMES} pilotent directement les options ici — les
    // deux tableaux DOIVENT rester en phase.
    @ConfigDropdown(name = "Langue", description = "Langue de l'interface du launcher (menu, réglages, HUD...). Certains écrans avancés (navigateur Modrinth) restent en français pour le moment.",
        category = "Général", options = { "Français", "English", "Español", "Deutsch", "Português (Brasil)", "Русский" })
    public int language = 0;

    // Visibilité du HUD à travers les écrans vanilla/mod — réglage GLOBAL
    // (tous les modules HUD), demandé explicitement par l'utilisateur suite
    // à "le HUD disparaît à la moindre interface". Par défaut : visible dans
    // Inventaire/Conteneurs/Tchat (choix explicite de l'utilisateur), PAS le
    // menu pause (jamais demandé, comportement vanilla-like conservé — voir
    // HudOverlayRenderer.renderPersistent/HudScreenKind).
    @ConfigToggle(name = "Afficher dans l'inventaire", description = "Garde le HUD visible quand l'inventaire du joueur (touche E, sans conteneur ouvert) est ouvert.", category = "HUD en jeu")
    public boolean showHudInInventory = true;

    @ConfigToggle(name = "Afficher dans les conteneurs", description = "Garde le HUD visible dans les écrans de conteneur (coffre, four, table de craft...).", category = "HUD en jeu")
    public boolean showHudInContainers = true;

    @ConfigToggle(name = "Afficher dans le tchat", description = "Garde le HUD visible quand la zone de saisie du tchat est ouverte.", category = "HUD en jeu")
    public boolean showHudInChat = true;

    private GlobalUiSettings() {
        super("ui-settings", "Paramètres", "Réglages généraux de l'interface", true);
        // Charge les valeurs persistées AVANT de les appliquer — ce singleton
        // n'est JAMAIS enregistré dans ModuleRegistry (voir javadoc de
        // classe), donc jamais couvert par l'appel générique
        // HudConfigStore.applyTo() fait dans ModuleRegistry.register() pour
        // tout autre module. Sans cet appel explicite, "Taille de
        // l'interface" (et tous les autres réglages ici) revenaient
        // silencieusement à leur valeur par défaut codée en dur à CHAQUE
        // relance du jeu, quoi que l'utilisateur ait choisi la session
        // précédente — voir aussi HudConfigStore.save() (même singleton
        // ajouté explicitement à la sérialisation) et GlobalUiRenderMixin/189
        // (force le chargement de CETTE classe dès la première frame, sinon
        // UiTheme.UI_SCALE restait bloqué sur la valeur de départ codée en
        // dur de UiTheme jusqu'à la première visite de l'écran "Paramètres").
        HudConfigStore.applyTo(this);
        onConfigChanged();
    }

    @Override
    public void onConfigChanged() {
        Lang.setLanguage(language);
        UiTheme.applyMode(themeMode == 1 ? UiTheme.Mode.LIGHT : UiTheme.Mode.DARK);

        float hudAlpha = Math.max(0f, Math.min(1f, hudOpacity / 100f));
        HudPanelRenderer.PANEL_BG = new UiColor(BASE_HUD_BG.r, BASE_HUD_BG.g, BASE_HUD_BG.b, hudAlpha);

        // Voir javadoc du champ cornerRadius — HudPanelRenderer.RADIUS
        // (panneaux HUD), PLUS UiTheme.RADIUS_MD (cartes du menu, reste fixe
        // à sa valeur par défaut, jamais réassignée par ce réglage).
        HudPanelRenderer.RADIUS = cornerRadius;
        UiTheme.ACCENT = accentColor;
        UiTheme.ACCENT_DIM = new UiColor(accentColor.r, accentColor.g, accentColor.b, 70f / 255f);
        UiTheme.SIDEBAR_ACTIVE = new UiColor(accentColor.r, accentColor.g, accentColor.b, 34f / 255f);
        UiInputPoller.menuKeyName = menuKey;

        // Échelle décalée d'un cran vers le haut (demandé explicitement) :
        // l'ancienne "Normale" (1f) est maintenant "Petite", l'ancienne
        // "Grande" (1.18f) est maintenant "Normale", et un nouveau palier
        // "Grande" plus grand encore (1.4f) a été ajouté.
        UiTheme.UI_SCALE = uiSize == 0 ? 1f : uiSize == 2 ? 1.4f : 1.18f;
    }
}
