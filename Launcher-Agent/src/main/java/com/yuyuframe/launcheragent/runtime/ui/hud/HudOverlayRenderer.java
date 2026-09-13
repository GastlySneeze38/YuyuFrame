package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiTarget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.GameOptions;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;

import java.lang.reflect.Field;

/**
 * POLITIQUE d'affichage du HUD — décide QUOI montrer et QUAND ; le dessin
 * lui-même vit dans {@link HudRenderer} ({@code apigraphic}).
 *
 * <p>Cette coupe date du refacto du 2026-08-27 (« le système bas niveau du HUD
 * est dans runtime alors qu'il devrait être dans apigraphic »). Ce fichier
 * concentre tout ce qui NE POUVAIT PAS descendre dans le moteur graphique :
 * <ul>
 *   <li>{@link GlobalUiSettings} — un {@code LauncherModule} annoté
 *       {@code @Config}, donc de la politique applicative pure ;</li>
 *   <li>{@link HudScreenKind} — la connaissance des types d'écrans du JEU ;</li>
 *   <li>{@link #vanillaHudHidden()} — la lecture d'un champ vanilla par
 *       réflexion.</li>
 * </ul>
 * Faire descendre l'un de ces trois dans {@code apigraphic} y aurait fait
 * entrer soit les réglages de l'application, soit les classes de Minecraft.
 *
 * <p>La signature publique est INCHANGÉE (mêmes noms, mêmes paramètres) : les
 * 7 sites d'appel dans les Mixins de chaque bracket n'ont pas eu à bouger, ce
 * qui limite d'autant le risque d'un refacto non testable en jeu.
 *
 * <p>Pendant l'édition ({@code UiHudEditorScreen} ouvert), ce sont les
 * {@code UiHudBox} de l'écran qui dessinent (même rendu, voir
 * {@code HudPanelRenderer}) — cet overlay ne tourne donc jamais en même temps
 * qu'eux, pas de double-dessin.
 */
public final class HudOverlayRenderer {
    private HudOverlayRenderer() {}

    /**
     * {@code true} si le joueur a masqué l'interface vanilla (touche F1,
     * {@code GameOptions.hudHidden}) — demandé explicitement ("tout nos hud
     * doivent disparaître en F1") : nos éléments HUD/modules overlay n'avaient
     * jusqu'ici AUCUNE conscience de cet état vanilla, donc restaient affichés
     * alors que le HUD du jeu (barre de vie, hotbar...) disparaissait. Nom Yarn
     * {@code hudHidden} (intermédiaire {@code field_1842}, {@code field_948} en
     * 1.8.9) — nom RÉEL Mojang {@code hideGui} sur
     * {@code net.minecraft.client.Options}, vérifié par javap sur le jar client
     * 26.1.2 réel (Yarn jamais chargé sur ce bracket). Champ RUNTIME (pas
     * persisté).
     */
    public static boolean vanillaHudHidden() {
        // Tranche liée : accessor Mixin via AccessPoint.OPTIONS_HIDE_GUI, zéro
        // réflexion (2026-08-31). C'était le dernier accès réflexif du chemin
        // de rendu du HUD, appelé à chaque frame. Repli réflexif conservé pour
        // les tranches sans liaison, où ni Options ni l'accessor n'existent
        // sous ces noms.
        try {
            // La VALEUR, pas isBound() : un accès peut être déclaré par la
            // tranche et rester sans réponse si son accessor n'a pas été tissé.
            // Se contenter de isBound() rendrait le repli réflexif ci-dessous
            // inatteignable dans ce cas précis, alors que c'est exactement
            // celui pour lequel il existe.
            Object hidden = AccessorRegistry.get(AccessPoint.OPTIONS_HIDE_GUI, null);
            if (hidden instanceof Boolean) return (Boolean) hidden;
        } catch (Throwable ignored) {
            // NoClassDefFoundError attendu hors tranche liée — le repli
            // ci-dessous prend le relais, inutile de le journaliser à chaque
            // frame.
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return false;
            Field hudHiddenField = McReflect.field(options.getClass(),
                "net/minecraft/client/option/GameOptions", "hudHidden", "hideGui");
            if (hudHiddenField == null) return false;
            return hudHiddenField.getBoolean(options);
        } catch (Throwable t) {
            LauncherLog.err("[HudOverlayRenderer] vanillaHudHidden: " + t);
            return false;
        }
    }

    /**
     * Affichage HUD permanent, aucun écran ouvert — délègue le dessin à
     * {@link HudRenderer}.
     *
     * <p>Plus appelé sur 26.1.2 : ce bracket passe par
     * {@link #renderInVanillaGui}. Conservé pour les AUTRES brackets, dont les
     * mixins globaux ({@code GlobalUiPresentMixin}, {@code GlobalUiRenderMixin116},
     * {@code …1204}) l'appellent toujours après la présentation.
     */
    public static void render(UiRenderer renderer, int vpWidth, int vpHeight) {
        HudRenderer.drawAll(renderer, vpWidth, vpHeight);
    }

    /**
     * Rendu du HUD DEPUIS LA PASSE GUI de vanilla — voir
     * {@code docs/LauncherAgent/rendering-pipeline.md}.
     *
     * <p>Même dessin que {@link #render}, mais avec la cible
     * {@code VanillaGuiTarget} armée : panneaux et texte atterrissent dans
     * l'état de GUI de vanilla, à la profondeur du hook, au lieu de la file
     * Blaze3D vidée après la présentation. C'est ce qui met le HUD SOUS le
     * chat et ses panneaux SOUS les icônes d'item.
     *
     * <p>Ne fait rien si la cible n'a pas pu être armée — cas qui ne devrait
     * pas se produire sur ce bracket, puisque c'est désormais le SEUL chemin
     * de rendu du HUD en 26.1.2 (l'appel après présentation a été retiré de
     * {@code GlobalUiPresentMixin261}).
     *
     * <p>{@code end()} dans un {@code finally} : une cible restée armée
     * détournerait tout le rendu suivant, écrans de menu compris, vers un état
     * de GUI périmé.
     */
    public static void renderInVanillaGui(Object hookContext) {
        if (vanillaHudHidden()) return;
        UiInputPoller poller = UiInputPoller.ACTIVE;
        if (poller == null) return;

        // MÊME politique de visibilité que l'ancien chemin, qui la tenait du
        // mixin de présentation : rien en jeu ne doit changer selon QUI
        // déclenche le rendu.
        //   - aucun écran            → tous les éléments
        //   - écran vanilla/mod      → seulement ceux autorisés pour ce TYPE
        //                              d'écran (voir shouldShowPersistent)
        //   - un de NOS écrans       → aucun HUD, le menu occupe l'écran
        // screenObject() et NON screen() : cette méthode tourne sur toutes les
        // versions, et la variante typée lierait le Screen 26.1.2 (voir sa javadoc).
        Object screen = ClientData.screenObject();
        if (screen instanceof UiDrawable) return;

        int w = poller.fbWidth, h = poller.fbHeight;
        UiRenderer renderer = UiRenderer.get(HudOverlayRenderer.class.getClassLoader());
        if (!VanillaGuiTarget.begin(hookContext, w, h)) return;
        try {
            // AVANT le HUD : l'ordre de soumission fait la profondeur dans
            // l'état de GUI, et un overlay (teinte vie basse, indicateurs de
            // saturation) doit passer SOUS nos propres panneaux.
            //
            // Appelé dans LES DEUX cas depuis le 2026-08-31 (retour
            // utilisateur : « il ne s'affiche plus dans le chat, il faut
            // qu'il s'affiche de partout ») — le HUD vanilla, lui, continue
            // d'être dessiné derrière les écrans, donc un overlay collé à la
            // barre de faim doit suivre. C'est chaque module qui décide, via
            // renderInVanillaGuiWhenScreenOpen() : la restriction d'origine à
            // « aucun écran ouvert » venait de l'ancien chemin après
            // présentation, pas d'un choix réfléchi.
            com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry.renderInVanillaGuiAll(
                renderer, w, h, screen != null);

            if (screen == null) {
                HudRenderer.drawAll(renderer, w, h);
            } else {
                HudRenderer.drawPersistent(renderer, shouldShowPersistent(screen), w, h);
            }
        } catch (Throwable t) {
            LauncherLog.err("[HudOverlayRenderer] renderInVanillaGui: " + t);
        } finally {
            VanillaGuiTarget.end();
        }
    }

    /**
     * Variante appelée quand un écran NON custom (chat, inventaire, tout autre
     * GUI vanilla/mod) est ouvert — voir le Mixin global de chaque bracket,
     * branché juste avant son propre "return" pour ce cas.
     *
     * <p>Ne dessine que les éléments dont {@link HudElement#showWhenScreenOpen}
     * est vrai (opt-in par élément) OU dont le réglage GLOBAL correspondant au
     * TYPE d'écran ouvert (voir {@link HudScreenKind},
     * {@link GlobalUiSettings#showHudInInventory}/InContainers/InChat) est
     * activé — demandé explicitement ("le HUD disparaît à la moindre
     * interface") : réglage GLOBAL (tous les modules HUD), granularité PAR TYPE
     * d'écran, visible par défaut dans Inventaire/Conteneurs/Tchat (PAS le menu
     * pause).
     *
     * @param currentScreen l'écran vanilla/mod actuellement ouvert (jamais
     *     {@code null} ici — {@link #render} couvre déjà ce cas).
     */
    public static void renderPersistent(UiRenderer renderer, Object currentScreen, int vpWidth, int vpHeight) {
        HudRenderer.drawPersistent(renderer, shouldShowPersistent(currentScreen), vpWidth, vpHeight);
    }

    /** Décision de visibilité globale selon le TYPE d'écran ouvert — extraite pour rester lisible et testable indépendamment du dessin. */
    private static boolean shouldShowPersistent(Object currentScreen) {
        switch (HudScreenKind.classify(currentScreen)) {
            case INVENTORY: return GlobalUiSettings.INSTANCE.showHudInInventory;
            case CONTAINER: return GlobalUiSettings.INSTANCE.showHudInContainers;
            case CHAT:      return GlobalUiSettings.INSTANCE.showHudInChat;
            default:        return false;
        }
    }
}
