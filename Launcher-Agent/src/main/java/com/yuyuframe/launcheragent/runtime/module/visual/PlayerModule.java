package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.runtime.module.optimization.OptimizationModule;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * « Joueur » : comment les autres joueurs YuyuFrame apparaissent à l'écran —
 * le logo devant leur pseudo, le fond de leur étiquette, la couleur de leurs
 * lettres, et la même chose dans la liste Tab.
 *
 * <p>Le module ne dessine rien : il tient les réglages et les pose dans
 * {@link PlayerStyle}, que lisent l'étiquette au-dessus de la tête et la liste
 * Tab de chaque version (1.8.9 : {@code LabelBatch189},
 * {@code PlayerListStyle189}). Éteint, aucun pseudo n'est touché et plus
 * aucune question ne part vers le serveur.
 *
 * <p>Il ne règle QUE ce que ce joueur voit. Apparaître soi-même comme joueur
 * YuyuFrame aux yeux des autres se décide dans le launcher (Paramètres →
 * Confidentialité).
 *
 * <p>1.8.9 seulement pour l'instant.
 */
public final class PlayerModule extends LauncherModule {

    public boolean logo = true;
    public int plate = PlayerStyle.PLATE_NEON;
    public int textMode = PlayerStyle.TEXT_IF_UNCOLORED;
    public int animation = PlayerStyle.ANIM_BREATH;
    /** Le violet et le bleu du logo. */
    public UiColor colorA = new UiColor(180, 60, 255, 255);
    public UiColor colorB = new UiColor(40, 224, 255, 255);
    public boolean tab = true;
    public boolean preview;
    public boolean ownName;

    /** Lu par {@link #rendererChanged()}, appelé depuis un autre module. */
    private static volatile PlayerModule instance;

    public PlayerModule() {
        super("player", "Joueur", "Logo, fond et couleurs du pseudo des joueurs YuyuFrame",
            "Pseudos des joueurs YuyuFrame", true);
        iconUrl = icons8("user");
        instance = this;
        apply();
    }

    /**
     * L'étiquette au-dessus de la tête n'est stylisée que par le dessin groupé
     * du renderer de terrain ({@code LabelBatch189}) ; la liste Tab, elle, n'en
     * dépend pas.
     */
    private static boolean rendererOn() {
        return RendererSwitch.wanted() || RendererSwitch.active();
    }

    /** Le renderer vient d'être allumé ou éteint : l'avertissement doit apparaître ou disparaître. */
    public static void rendererChanged() {
        PlayerModule module = instance;
        if (module != null) {
            module.invalidateSettings();
        }
    }

    @Override
    protected void settings(SettingList s) {
        // Tant que le renderer est éteint : dire pourquoi l'étiquette ne change
        // pas, et offrir de l'allumer d'ici. La liste est refaite quand il
        // change d'état (voir rendererChanged), donc ces deux lignes s'en vont seules.
        if (!rendererOn()) {
            s.warning("renderer-warning", "Renderer de terrain éteint",
                "Sans lui, seule la liste Tab est stylisée : l'étiquette au-dessus de la tête garde son aspect d'origine.",
                "Étiquette");
            if (RendererSwitch.available()) {
                s.action("renderer-enable", "Activer le renderer", "", "Étiquette", PlayerModule::enableRenderer);
            }
        }
        s.toggle("logo", "Logo YuyuFrame", "Affiche le logo devant le pseudo", "Étiquette",
            null, () -> logo, v -> { logo = v; apply(); });
        s.dropdown("plate", "Fond de l'étiquette", "Le décor dessiné derrière le pseudo",
            "Étiquette", new String[]{ "Vanilla", "Néon", "Aurore", "Verre", "Comète" }, null,
            () -> plate, v -> { plate = v; apply(); });
        s.dropdown("textMode", "Couleur du pseudo",
            "Par défaut, une couleur d'équipe ou de grade posée par le serveur est gardée",
            "Étiquette", new String[]{ "Inchangée", "Si non colorée", "Toujours" }, null,
            () -> textMode, v -> { textMode = v; apply(); });
        s.dropdown("animation", "Animation", "Fait vivre les couleurs du pseudo",
            "Étiquette", new String[]{ "Aucune", "Respiration", "Vague", "Arc-en-ciel" }, null,
            () -> animation, v -> { animation = v; apply(); });
        s.color("colorA", "Première couleur", "Couleurs", () -> colorA, v -> { colorA = v; apply(); });
        s.color("colorB", "Seconde couleur", "Couleurs", () -> colorB, v -> { colorB = v; apply(); });
        s.toggle("tab", "Liste Tab", "Applique le même style dans la liste des joueurs", "Où",
            null, () -> tab, v -> { tab = v; apply(); });
        s.toggle("ownName", "Mon pseudo", "Affiche votre pseudo au-dessus de votre tête en vue extérieure",
            "Où", null, () -> ownName, v -> { ownName = v; apply(); });
        s.toggle("preview", "Aperçu sur tous les joueurs",
            "Pour régler l'apparence sans attendre de croiser un joueur YuyuFrame",
            "Où", null, () -> preview, v -> { preview = v; apply(); });
    }

    private static void enableRenderer() {
        LauncherModule optimization = ModuleRegistry.get("optimization");
        if (optimization instanceof OptimizationModule) {
            ((OptimizationModule) optimization).enableTerrainRenderer();
        }
        // Au cas où la consigne n'aurait pas changé (renderer déjà demandé) : la liste est refaite quand même.
        rendererChanged();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        apply();
    }

    /** Recopie les réglages là où le dessin les lit, à chaque image, sans passer par le registre des modules. */
    private void apply() {
        PlayerStyle.logo = logo;
        PlayerStyle.plate = plate;
        PlayerStyle.textMode = textMode;
        PlayerStyle.animation = animation;
        PlayerStyle.colorA = rgb(colorA);
        PlayerStyle.colorB = rgb(colorB);
        PlayerStyle.tab = tab;
        PlayerStyle.preview = preview;
        PlayerStyle.ownLabel = ownName && isEnabled();
        PlayerStyle.active = isEnabled();
    }

    private static int rgb(UiColor c) {
        return (int) (c.r * 255F) << 16 | (int) (c.g * 255F) << 8 | (int) (c.b * 255F);
    }
}
