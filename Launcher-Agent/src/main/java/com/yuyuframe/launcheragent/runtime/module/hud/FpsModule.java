package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;

import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Port de PvP-Mod FpsConfig/FpsHud — sa propre carte, comme dans la
 * référence. Le module fournit UNIQUEMENT les données (voir ContentSource
 * nichée ci-dessous) — tout le rendu passe par l'API générique partagée
 * (runtime.ui.hud : HudElement/HudPanelRenderer/HudOverlayRenderer), jamais de
 * code de dessin ici.
 */
public final class FpsModule extends SingleHudModule {
    public FpsModule() {
        super("fps", "FPS", "Affiche le nombre d'images par seconde", true,
            new HudElement("fps", "FPS", HudAnchor.TOP_LEFT, 8f, 8f, new ContentSource()));
        hudElement().textColor = new UiColor(100, 180, 255, 255);
        hudElement().accentSuffix = " FPS";
        iconUrl = icons8("speedometer");
    }

    /** FPS réel courant — accessor Mixin via ClientData.fps(). */
    private static final class ContentSource implements HudElement.ContentSource {
        @Override
        public String[] lines() {
            // FPS par l'accessor Mixin, via ClientData — zéro réflexion.
            // Le repli réflexif multi-bracket (champ "currentFps"→"fps") a été
            // supprimé le 2026-08-27 : il ne servait que les brackets
            // obfusqués, où ce module ne fonctionne de toute façon plus depuis
            // que tout l'accès aux données du jeu passe par les accessors.
            int fps = ClientData.fps();
            return new String[]{ fps < 0 ? "-- FPS" : fps + " FPS" };
        }
    }
}
