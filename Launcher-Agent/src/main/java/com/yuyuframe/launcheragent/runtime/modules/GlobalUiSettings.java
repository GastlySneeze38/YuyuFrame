package com.yuyuframe.launcheragent.runtime.modules;

import com.yuyuframe.launcheragent.runtime.modules.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigKeybind;
import com.yuyuframe.launcheragent.runtime.modules.config.ConfigSlider;
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

    public static final GlobalUiSettings INSTANCE = new GlobalUiSettings();

    @ConfigSlider(name = "Opacité des cartes", description = "Transparence des cartes de mods et panneaux de config.",
        category = "Apparence", min = 10f, max = 100f, step = 1f)
    public float cardOpacity = 100f;

    @ConfigSlider(name = "Rayon des coins", description = "Arrondi des coins des cartes de mods.",
        category = "Apparence", min = 0f, max = 16f, step = 1f)
    public float cornerRadius = UiTheme.RADIUS_MD;

    @ConfigColor(name = "Couleur d'accent", description = "Couleur principale utilisée dans tout le menu.", category = "Apparence")
    public UiColor accentColor = UiTheme.ACCENT;

    @ConfigKeybind(name = "Touche du menu", description = "Touche qui ouvre/ferme le menu YuyuFrame en jeu.", category = "Général")
    public String menuKey = UiInputPoller.menuKeyName;

    private GlobalUiSettings() {
        super("ui-settings", "Paramètres", "Réglages généraux de l'interface", true);
        onConfigChanged(); // applique les valeurs par défaut dès la construction
    }

    @Override
    public void onConfigChanged() {
        float alpha = Math.max(0f, Math.min(1f, cardOpacity / 100f));
        UiTheme.CARD_BG = new UiColor(BASE_CARD_BG.r, BASE_CARD_BG.g, BASE_CARD_BG.b, alpha);
        UiTheme.CARD_HOVER = new UiColor(BASE_CARD_HOVER.r, BASE_CARD_HOVER.g, BASE_CARD_HOVER.b, alpha);
        UiTheme.RADIUS_MD = cornerRadius;
        UiTheme.ACCENT = accentColor;
        UiTheme.ACCENT_DIM = new UiColor(accentColor.r, accentColor.g, accentColor.b, 70f / 255f);
        UiTheme.SIDEBAR_ACTIVE = new UiColor(accentColor.r, accentColor.g, accentColor.b, 34f / 255f);
        UiInputPoller.menuKeyName = menuKey;
    }
}
