package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;

import java.lang.reflect.Field;

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

    /** FPS réel courant — lecture du champ Minecraft.currentFps. */
    private static final class ContentSource implements HudElement.ContentSource {
        @Override
        public String[] lines() {
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return new String[]{ "-- FPS" };
                // 26.1+ : champ RENOMMÉ "currentFps"→"fps" (STATIC désormais,
                // vérifié par javap sur le jar client 26.1.2 réel) — Field.getInt(Object)
                // reste valide sur un champ static quel que soit l'objet passé.
                Field fpsField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "currentFps", "fps");
                if (fpsField == null) return new String[]{ "-- FPS" };
                return new String[]{ fpsField.getInt(mc) + " FPS" };
            } catch (Throwable t) {
                return new String[]{ "-- FPS" };
            }
        }
    }
}
