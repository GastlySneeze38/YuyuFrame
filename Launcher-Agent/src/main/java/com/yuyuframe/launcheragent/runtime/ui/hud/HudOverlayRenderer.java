package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

import java.lang.reflect.Field;

/**
 * Affichage HUD PERMANENT — dessine chaque élément enregistré
 * (HudRegistry.elements()) à sa position courante, chaque frame où AUCUN
 * écran n'est ouvert (voir GlobalUiRenderMixin/189, branché uniquement dans
 * la branche currentScreen == null : les vanilla HUD elements eux-mêmes ne
 * s'affichent pas quand un écran est ouvert, même règle ici).
 *
 * Pendant l'édition (UiHudEditorScreen ouvert), ce sont les UiHudBox de
 * l'écran lui-même qui dessinent (même rendu, voir HudPanelRenderer) — cet
 * overlay ne tourne donc jamais en même temps qu'eux, pas de double-dessin.
 */
public final class HudOverlayRenderer {
    private HudOverlayRenderer() {}

    /**
     * {@code true} si le joueur a masqué l'interface vanilla (touche F1,
     * {@code GameOptions.hudHidden}) — demandé explicitement ("tout nos hud
     * doivent disparaître en F1") : nos éléments HUD/modules overlay
     * n'avaient jusqu'ici AUCUNE conscience de cet état vanilla, donc
     * restaient affichés alors que le HUD du jeu (barre de vie, hotbar...)
     * disparaissait. Nom Yarn {@code hudHidden} (intermédiaire {@code
     * field_1842}, {@code field_948} en 1.8.9) — nom RÉEL Mojang {@code
     * hideGui} sur {@code net.minecraft.client.Options}, vérifié par javap
     * sur le jar client 26.1.2 réel (Yarn jamais chargé sur ce bracket).
     * Champ RUNTIME (pas persisté), jamais vu nulle part ailleurs dans ce
     * moteur avant cet ajout.
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

    public static void render(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (HudElement element : HudRegistry.elements()) {
            // Voir HudElement.refreshSize() : un contenu de largeur variable
            // (FPS/Ping) doit être remesuré à CHAQUE frame, pas une seule fois
            // à la construction — sinon la boîte reste figée sur le texte de
            // repli initial pendant que le vrai texte affiché change de largeur.
            element.refreshSize();
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }

    /**
     * Variante appelée quand un écran NON custom (chat, inventaire, tout
     * autre GUI vanilla/mod) est ouvert — voir le Mixin global, branché juste
     * avant son propre "return" pour ce cas. Ne dessine que les éléments
     * dont {@link HudElement#showWhenScreenOpen} est vrai (opt-in par
     * élément, pas utilisé actuellement par aucun module) OU dont le réglage
     * GLOBAL correspondant au TYPE d'écran ouvert (voir {@link HudScreenKind},
     * {@link GlobalUiSettings#showHudInInventory}/InContainers/InChat) est
     * activé — demandé explicitement par l'utilisateur ("le HUD disparaît à
     * la moindre interface"), réglage GLOBAL (tous les modules HUD),
     * granularité PAR TYPE d'écran, visible par défaut dans
     * Inventaire/Conteneurs/Tchat (PAS le menu pause).
     *
     * @param currentScreen l'écran vanilla/mod actuellement ouvert (jamais
     *     {@code null} ici — {@link #render} couvre déjà le cas
     *     currentScreen == null) — classifié via {@link HudScreenKind}.
     */
    public static void renderPersistent(UiRenderer renderer, Object currentScreen, int vpWidth, int vpHeight) {
        HudScreenKind kind = HudScreenKind.classify(currentScreen);
        boolean globalShow;
        switch (kind) {
            case INVENTORY: globalShow = GlobalUiSettings.INSTANCE.showHudInInventory; break;
            case CONTAINER: globalShow = GlobalUiSettings.INSTANCE.showHudInContainers; break;
            case CHAT:      globalShow = GlobalUiSettings.INSTANCE.showHudInChat; break;
            default:        globalShow = false; break;
        }
        for (HudElement element : HudRegistry.elements()) {
            if (!globalShow && !element.showWhenScreenOpen) continue;
            element.refreshSize();
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }
}
