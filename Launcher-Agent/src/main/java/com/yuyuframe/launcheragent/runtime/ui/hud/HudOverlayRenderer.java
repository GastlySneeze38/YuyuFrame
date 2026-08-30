package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRenderer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;

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

    /** Affichage HUD permanent, aucun écran ouvert — délègue le dessin à {@link HudRenderer}. */
    public static void render(UiRenderer renderer, int vpWidth, int vpHeight) {
        HudRenderer.drawAll(renderer, vpWidth, vpHeight);
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
