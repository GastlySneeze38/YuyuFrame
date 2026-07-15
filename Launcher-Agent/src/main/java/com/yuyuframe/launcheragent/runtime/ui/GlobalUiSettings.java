package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudPanelRenderer;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
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

    // Couleurs de base d'origine (UiTheme littéral) — indépendantes de l'état
    // COURANT (déjà muté) de UiTheme, pour ne jamais faire dériver la teinte
    // à chaque rappel de onConfigChanged(). DOIT rester déclaré AVANT INSTANCE
    // ci-dessous : les initialiseurs statiques s'exécutent dans l'ordre
    // TEXTUEL, et le constructeur de INSTANCE appelle onConfigChanged() (qui
    // lit ces champs) — les avoir après aurait laissé BASE_CARD_BG/HOVER à
    // null au moment de cet appel (NullPointerException → ExceptionInInitializerError,
    // observé en jeu : le clic sur "Parametres" ne faisait plus jamais rien).
    private static final UiColor BASE_CARD_BG = new UiColor(31, 31, 39, 255);
    private static final UiColor BASE_CARD_HOVER = new UiColor(40, 40, 50, 255);
    private static final UiColor BASE_HUD_BG = new UiColor(10, 10, 14, 120);

    public static final GlobalUiSettings INSTANCE = new GlobalUiSettings();

    @ConfigSlider(name = "Opacité des cartes (menu)", description = "Transparence des cartes de mods et panneaux de config du MENU — pas les panneaux HUD affichés en jeu (voir \"Opacité du HUD\" ci-dessous).",
        category = "Apparence", min = 10f, max = 100f, step = 1f)
    public float cardOpacity = 100f;

    @ConfigSlider(name = "Opacité du HUD (en jeu)", description = "Transparence des panneaux HUD affichés en jeu (FPS, ping, coordonnées...) — pas les cartes du menu.",
        category = "Apparence", min = 10f, max = 100f, step = 1f)
    public float hudOpacity = BASE_HUD_BG.a * 100f;

    @ConfigSlider(name = "Rayon des coins", description = "Arrondi des coins des cartes de mods.",
        category = "Apparence", min = 0f, max = 16f, step = 1f)
    public float cornerRadius = UiTheme.RADIUS_MD;

    @ConfigColor(name = "Couleur d'accent", description = "Couleur principale utilisée dans tout le menu.", category = "Apparence")
    public UiColor accentColor = UiTheme.ACCENT;

    @ConfigDropdown(name = "Taille de l'interface", description = "Échelle générale du menu (cartes, texte, boutons, réglages...) — pas la taille des éléments HUD affichés en jeu, qui se règlent individuellement (voir chaque module).",
        category = "Apparence", options = { "Petite", "Normale", "Grande" })
    public int uiSize = 1;

    @ConfigKeybind(name = "Touche du menu", description = "Touche qui ouvre/ferme le menu YuyuFrame en jeu.", category = "Général")
    public String menuKey = UiInputPoller.menuKeyName;

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
        float alpha = Math.max(0f, Math.min(1f, cardOpacity / 100f));
        UiTheme.CARD_BG = new UiColor(BASE_CARD_BG.r, BASE_CARD_BG.g, BASE_CARD_BG.b, alpha);
        UiTheme.CARD_HOVER = new UiColor(BASE_CARD_HOVER.r, BASE_CARD_HOVER.g, BASE_CARD_HOVER.b, alpha);

        float hudAlpha = Math.max(0f, Math.min(1f, hudOpacity / 100f));
        HudPanelRenderer.PANEL_BG = new UiColor(BASE_HUD_BG.r, BASE_HUD_BG.g, BASE_HUD_BG.b, hudAlpha);

        UiTheme.RADIUS_MD = cornerRadius;
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
