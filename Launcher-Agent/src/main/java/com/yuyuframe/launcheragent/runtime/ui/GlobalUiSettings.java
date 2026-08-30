package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.apigraphic.hud.HudPanelRenderer;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

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
    public int themeMode = 0;

    public float hudOpacity = BASE_HUD_BG.a * 100f;

    // BUG TROUVÉ (retour utilisateur : "c'est le rayon des coins des HUD et
    // pas du menu principal") — pilotait UiTheme.RADIUS_MD, qui n'est
    // utilisé QUE par les cartes/panneaux du MENU (config, mods...) : les
    // panneaux HUD, eux, ont TOUJOURS leur propre constante dédiée
    // (HudPanelRenderer.RADIUS, jamais alignée sur RADIUS_MD par choix
    // délibéré — voir sa javadoc), donc ce réglage n'avait en réalité AUCUN
    // effet sur le HUD. Pilote désormais HudPanelRenderer.RADIUS, plus
    // UiTheme.RADIUS_MD (qui reste fixe à sa valeur par défaut pour le menu).
    public float cornerRadius = HudPanelRenderer.RADIUS;

    public UiColor accentColor = UiTheme.ACCENT;

    public int uiSize = 1;

    // Demandé explicitement ("plusieurs agencements comme dans Lunar, choix
    // via une icône à côté de la barre de recherche") — le champ existe ICI
    // (persisté comme tout autre réglage @ConfigDropdown, voir HudConfigStore)
    // uniquement pour la sauvegarde disque ; le changement rapide se fait
    // depuis UiMainMenuScreen (icône dédiée), pas depuis cet écran Paramètres,
    // mais rien n'empêche de le faire ici aussi (même champ, même source de
    // vérité). Lu directement par UiMainMenuScreen.rebuildAll() à chaque
    // reconstruction de la grille.
    public int cardLayout = 2; // Grille d'icônes par défaut — retour utilisateur : "la grille est parfaite, mets-la par défaut".

    // Demandé explicitement ("améliore le système de favori... rajoute un
    // settings pour mettre à l'écart les modules en favori") — les favoris
    // remontent TOUJOURS en tête de grille (voir UiMainMenuScreen.rebuildAll(),
    // comportement inconditionnel, PAS piloté par ce réglage) ; celui-ci ne
    // pilote QUE l'affichage d'une section séparée avec titres ("FAVORIS" /
    // "AUTRES MODULES") au lieu d'une simple grille continue.
    public boolean separateFavorites = true; // Activé par défaut — retour utilisateur explicite.

    public String menuKey = UiInputPoller.menuKeyName;

    // Choix de langue (demande explicite : "ajoute le fait que on puisse
    // choisir notre langue et donc avoir plusieurs fichier de traduction") —
    // voir Lang pour le mécanisme (texte source français utilisé comme clé
    // de recherche dans lang/<id>.json, jamais de clé abstraite dédiée à
    // maintenir dans chaque module). Les index de {@link Lang#LANGUAGE_IDS}/
    // {@link Lang#LANGUAGE_NAMES} pilotent directement les options ici — les
    // deux tableaux DOIVENT rester en phase.
    public int language = 0;

    // Visibilité du HUD à travers les écrans vanilla/mod — réglage GLOBAL
    // (tous les modules HUD), demandé explicitement par l'utilisateur suite
    // à "le HUD disparaît à la moindre interface". Par défaut : visible dans
    // Inventaire/Conteneurs/Tchat (choix explicite de l'utilisateur), PAS le
    // menu pause (jamais demandé, comportement vanilla-like conservé — voir
    // HudOverlayRenderer.renderPersistent/HudScreenKind).
    public boolean showHudInInventory = true;

    public boolean showHudInContainers = true;

    public boolean showHudInChat = true;

    /**
     * Désactivé par défaut, délibérément : contrairement aux panneaux des
     * écrans, ceux du HUD sont affichés EN PERMANENCE pendant le jeu. Le flou
     * a un coût par frame en pleine action, et un aplat très transparent reste
     * souvent le plus lisible sur un décor qui bouge vite — c'est donc un
     * choix esthétique à faire, pas un défaut à imposer.
     */
    public boolean hudGlassBackground = false;

    /**
     * Trois catégories, dans l'ordre d'apparition des onglets : "Apparence",
     * "Général", "HUD en jeu" — l'ordre venait auparavant de celui des champs.
     */
    @Override
    protected void settings(SettingList s) {
        s.dropdown("themeMode", "Thème",
            "Palette générale de l'interface — sombre (par défaut) ou claire.",
            "Apparence", new String[]{ "Sombre", "Clair" }, null,
            () -> themeMode, v -> themeMode = v);
        s.slider("hudOpacity", "Opacité du HUD (en jeu)",
            "Transparence des panneaux HUD affichés en jeu (FPS, ping, coordonnées...) — pas les cartes du menu.",
            "Apparence", 10f, 100f, 1f, null, () -> hudOpacity, v -> hudOpacity = v);
        s.slider("cornerRadius", "Rayon des coins (HUD)",
            "Arrondi des coins des panneaux HUD affichés en jeu — pas les cartes du menu.",
            "Apparence", 0f, 16f, 1f, null, () -> cornerRadius, v -> cornerRadius = v);
        s.color("accentColor", "Couleur d'accent", "Couleur principale utilisée dans tout le menu.",
            "Apparence", null, () -> accentColor, v -> accentColor = v);
        s.dropdown("uiSize", "Taille de l'interface",
            "Échelle générale du menu (cartes, texte, boutons, réglages...) — pas la taille des éléments HUD affichés en jeu, qui se règlent individuellement (voir chaque module).",
            "Apparence", new String[]{ "Petite", "Normale", "Grande" }, null,
            () -> uiSize, v -> uiSize = v);
        s.dropdown("cardLayout", "Affichage des cartes (menu)",
            "Disposition des cartes de mods dans le menu principal.",
            "Apparence", new String[]{ "Détaillé", "Compacte", "Grille d'icônes" }, null,
            () -> cardLayout, v -> cardLayout = v);
        s.toggle("separateFavorites", "Séparer les favoris",
            "Affiche les mods favoris dans une section dédiée, avec un titre, en haut de la grille du menu principal.",
            "Apparence", null, () -> separateFavorites, v -> separateFavorites = v);

        s.keybind("menuKey", "Touche du menu", "Touche qui ouvre/ferme le menu YuyuFrame en jeu.",
            "Général", null, () -> menuKey, v -> menuKey = v);
        s.dropdown("language", "Langue",
            "Langue de l'interface du launcher (menu, réglages, HUD...). Certains écrans avancés (navigateur Modrinth) restent en français pour le moment.",
            "Général",
            new String[]{ "Français", "English", "Español", "Deutsch", "Português (Brasil)", "Русский" }, null,
            () -> language, v -> language = v);

        s.toggle("showHudInInventory", "Afficher dans l'inventaire",
            "Garde le HUD visible quand l'inventaire du joueur (touche E, sans conteneur ouvert) est ouvert.",
            "HUD en jeu", null, () -> showHudInInventory, v -> showHudInInventory = v);
        s.toggle("showHudInContainers", "Afficher dans les conteneurs",
            "Garde le HUD visible dans les écrans de conteneur (coffre, four, table de craft...).",
            "HUD en jeu", null, () -> showHudInContainers, v -> showHudInContainers = v);
        s.toggle("showHudInChat", "Afficher dans le tchat",
            "Garde le HUD visible quand la zone de saisie du tchat est ouverte.",
            "HUD en jeu", null, () -> showHudInChat, v -> showHudInChat = v);
        s.toggle("hudGlassBackground", "Fond flouté (HUD)",
            "Remplace l'aplat sombre derrière les panneaux HUD par un fond de verre dépoli qui floute le décor du jeu. Le réglage \"Opacité du HUD\" continue de s'appliquer.",
            "HUD en jeu", null, () -> hudGlassBackground, v -> hudGlassBackground = v);
    }

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
        HudPanelRenderer.USE_GLASS = hudGlassBackground;
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
