package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
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

    public PlayerModule() {
        super("player", "Joueur", "Stylise le pseudo des autres joueurs YuyuFrame : logo, fond de l'étiquette, couleurs, liste Tab",
            "Pseudos des joueurs YuyuFrame", true);
        iconUrl = icons8("user");
        apply();
    }

    @Override
    protected void settings(SettingList s) {
        s.toggle("logo", "Logo YuyuFrame", "Affiche le logo devant le pseudo", "Étiquette",
            null, () -> logo, v -> { logo = v; apply(); });
        s.dropdown("plate", "Fond de l'étiquette",
            "\"Néon\" : panneau sombre aux liserés lumineux. \"Aurore\" : les deux couleurs ondulent. \"Verre\" : verre fumé et barres de couleur. \"Comète\" : une comète file sous le pseudo. \"Vanilla\" : le fond d'origine.",
            "Étiquette", new String[]{ "Vanilla", "Néon", "Aurore", "Verre", "Comète" }, null,
            () -> plate, v -> { plate = v; apply(); });
        s.dropdown("textMode", "Couleur du pseudo",
            "Sur beaucoup de serveurs la couleur d'un pseudo indique l'équipe ou le grade : par défaut, seules les lettres que le serveur a laissées blanches sont recolorées.",
            "Étiquette", new String[]{ "Inchangée", "Si le serveur ne la fixe pas", "Toujours" }, null,
            () -> textMode, v -> { textMode = v; apply(); });
        s.dropdown("animation", "Animation",
            "\"Respiration\" : la lumière monte et descend lentement. \"Vague\" : le dégradé glisse le long du pseudo. \"Arc-en-ciel\" : toutes les couleurs défilent.",
            "Étiquette", new String[]{ "Aucune", "Respiration", "Vague", "Arc-en-ciel" }, null,
            () -> animation, v -> { animation = v; apply(); });
        s.color("colorA", "Première couleur", "Couleurs", () -> colorA, v -> { colorA = v; apply(); });
        s.color("colorB", "Seconde couleur", "Couleurs", () -> colorB, v -> { colorB = v; apply(); });
        s.toggle("tab", "Liste Tab", "Applique le même style dans la liste des joueurs", "Où",
            null, () -> tab, v -> { tab = v; apply(); });
        s.toggle("preview", "Aperçu sur tous les joueurs",
            "Stylise tous les joueurs, pas seulement ceux de YuyuFrame : pour régler l'apparence sans attendre d'en croiser un",
            "Où", null, () -> preview, v -> { preview = v; apply(); });
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
        PlayerStyle.active = isEnabled();
    }

    private static int rgb(UiColor c) {
        return (int) (c.r * 255F) << 16 | (int) (c.g * 255F) << 8 | (int) (c.b * 255F);
    }
}
