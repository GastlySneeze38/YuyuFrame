package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.runtime.module.optimization.OptimizationModule;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import com.yuyuframe.launcheragent.runtime.skin.SkinPoster;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * « Joueur » : deux choses indépendantes. Le <b>nametag</b> — son propre
 * pseudo au-dessus de sa tête, avec ou sans style — et le <b>style</b> des
 * joueurs YuyuFrame croisés : le logo devant leur pseudo, le fond de leur
 * étiquette, la couleur de leurs lettres, et la même chose dans la liste Tab.
 * L'un marche sans l'autre.
 *
 * <p>Le module ne dessine rien : il tient les réglages et les pose dans
 * {@link PlayerStyle}, que lisent l'étiquette au-dessus de la tête et la liste
 * Tab de chaque version (1.8.9 : {@code LabelBatch189},
 * {@code PlayerListStyle189}). Éteint, aucun pseudo n'est touché et plus
 * aucune question ne part vers le serveur.
 *
 * <p>Il ne règle QUE ce que ce joueur voit. Apparaître soi-même comme joueur
 * YuyuFrame aux yeux des autres se décide dans le launcher (Paramètres →
 * Confidentialité). Une exception, parce qu'elle touche aux deux sens à la
 * fois : « Serveurs Microsoft uniquement » ({@link #onlineOnly}), qui écarte
 * les serveurs de jeu en mode hors ligne — on n'y reconnaît plus personne et
 * l'on ne s'y annonce plus.
 */
public final class PlayerModule extends LauncherModule {

    public boolean logo = true;
    public int logoStyle = PlayerStyle.LOGO_PIXEL;
    // Par défaut, l'aspect du jeu : fond de vanilla, pseudo sans animation, et
    // le logo en pixels pour seule marque (choix de l'utilisateur, 2026-10-08).
    public int plate = PlayerStyle.PLATE_VANILLA;
    public int textMode = PlayerStyle.TEXT_IF_UNCOLORED;
    public int animation = PlayerStyle.ANIM_NONE;
    /** Le violet et le bleu du logo. */
    public UiColor colorA = new UiColor(122, 32, 255, 255);
    public UiColor colorB = new UiColor(0, 96, 255, 255);
    public boolean tab = true;
    public boolean preview;
    /** Le style s'applique aux joueurs YuyuFrame croisés ; coupé, le module peut ne servir qu'au nametag. */
    public boolean styleOthers = true;
    /** « Nametag » : son propre pseudo au-dessus de sa tête. */
    public boolean ownName = true;
    /** Ce nametag prend le style YuyuFrame, ou garde l'aspect du jeu. */
    public boolean ownStyle = true;
    /**
     * Ne reconnaître les joueurs YuyuFrame — et n'être reconnu — que sur les
     * serveurs qui vérifient les comptes Microsoft. Par défaut, les serveurs
     * en mode hors ligne comptent aussi : le pseudo n'y est pas prouvé, et une
     * empreinte de l'adresse du serveur est envoyée pour départager les homonymes.
     */
    public boolean onlineOnly;
    /**
     * Compte hors ligne : poser son skin sur les serveurs de jeu sans avoir à
     * taper la commande ({@link SkinPoster}). Allumé par défaut (choix de
     * l'utilisateur, 2026-10-10).
     */
    public boolean autoSkin = true;

    /**
     * Les versions récentes où le module existe. Elles n'en ont qu'une partie :
     * le nametag et le logo en pixels, l'étiquette et la liste Tab gardant
     * l'aspect de vanilla (choix de l'utilisateur, 2026-10-08). Les fonds, les
     * couleurs et l'animation restent propres à la 1.8.9.
     */
    public static final java.util.Set<String> MODERN_VERSIONS = java.util.Set.of("1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    /** 1.8.9 : tous les réglages. Ailleurs, seulement ceux du logo et du nametag. */
    private static final boolean FULL = "1.8.9".equals(System.getProperty("launcheragent.mcVersion", ""));

    /** Lu par {@link #rendererChanged()}, appelé depuis un autre module. */
    private static volatile PlayerModule instance;

    public PlayerModule() {
        super("player", "Joueur", "Votre nametag, et le style du pseudo des joueurs YuyuFrame",
            "Nametag et pseudos YuyuFrame", true);
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
        // Le nametag d'abord, à part : il ne dépend ni du style ni des autres joueurs.
        s.toggle("ownName", "Nametag", "Affiche votre pseudo au-dessus de votre tête en vue extérieure",
            "Nametag", null, () -> ownName, v -> { ownName = v; apply(); });
        s.toggle("ownStyle", "Style YuyuFrame", "Sinon, votre nametag garde l'aspect du jeu",
            "Nametag", null, () -> ownStyle, v -> { ownStyle = v; apply(); });
        // Tant que le renderer est éteint : dire pourquoi l'étiquette ne change
        // pas, et offrir de l'allumer d'ici. La liste est refaite quand il
        // change d'état (voir rendererChanged), donc ces deux lignes s'en vont seules.
        if (FULL && !rendererOn()) {
            s.warning("renderer-warning", "Renderer de terrain éteint",
                "Sans lui, seule la liste Tab est stylisée : l'étiquette au-dessus de la tête garde son aspect d'origine.",
                "Étiquette");
            if (RendererSwitch.available()) {
                s.action("renderer-enable", "Activer le renderer", "", "Étiquette", PlayerModule::enableRenderer);
            }
        }
        s.toggle("logo", "Logo YuyuFrame", "Affiche le logo devant le pseudo", "Étiquette",
            null, () -> logo, v -> { logo = v; apply(); });
        if (FULL) {
            legacySettings(s);
        }
        s.toggle("styleOthers", "Joueurs YuyuFrame", "Applique ce style aux joueurs YuyuFrame que vous croisez",
            "Où", null, () -> styleOthers, v -> { styleOthers = v; apply(); });
        s.toggle("onlineOnly", "Serveurs Microsoft uniquement",
            "Plus sûr : sur un serveur qui ne vérifie pas les comptes, un pseudo peut être usurpé, et une empreinte de l'adresse du serveur est envoyée pour vous y reconnaître",
            "Où", null, () -> onlineOnly, v -> { onlineOnly = v; apply(); });
        s.toggle("tab", "Liste Tab", "Applique le même style dans la liste des joueurs", "Où",
            null, () -> tab, v -> { tab = v; apply(); });
        s.toggle("preview", "Aperçu sur tous les joueurs",
            "Pour régler l'apparence sans attendre de croiser un joueur YuyuFrame",
            "Où", null, () -> preview, v -> { preview = v; apply(); });
        // Compte hors ligne : le skin choisi dans le launcher. Voir runtime/skin.
        s.info("skin-info", "Compte hors ligne",
            "Votre skin vient du launcher. Les autres joueurs le voient sur les serveurs qui ont la commande /skin (plugin SkinsRestorer).",
            "Skin");
        s.toggle("autoSkin", "Poser mon skin automatiquement",
            "Envoie /skin url à votre place en arrivant sur un serveur, une seule fois par serveur",
            "Skin", null, () -> autoSkin, v -> { autoSkin = v; apply(); });
        s.action("skin-post", "Poser mon skin sur ce serveur", "Envoie la commande maintenant", "Skin",
            SkinPoster::postNow);
    }

    /** Ce que seule la 1.8.9 sait dessiner : le choix du logo, les fonds, les couleurs du pseudo. */
    private void legacySettings(SettingList s) {
        s.dropdown("logoStyle", "Style du logo", "En pixels comme une texture du jeu, ou tracé à vos couleurs",
            "Étiquette", new String[]{ "Pixel", "Contours" }, null,
            () -> logoStyle, v -> { logoStyle = v; apply(); });
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
        PlayerStyle.logoStyle = logoStyle;
        PlayerStyle.ownLabel = ownName && isEnabled();
        PlayerStyle.ownStyled = ownName && ownStyle && isEnabled();
        PlayerStyle.enabled = isEnabled();
        PlayerStyle.active = styleOthers && isEnabled();
        // Vaut même module éteint : c'est aussi ce qui décide si l'on s'annonce sur un serveur hors ligne.
        PlayerStyle.onlineOnly = onlineOnly;
        // Vaut même module éteint, comme la ligne du dessus : le skin n'a rien à voir avec le nametag.
        SkinPoster.auto = autoSkin;
    }

    private static int rgb(UiColor c) {
        return (int) (c.r * 255F) << 16 | (int) (c.g * 255F) << 8 | (int) (c.b * 255F);
    }
}
